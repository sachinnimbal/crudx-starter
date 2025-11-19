package io.github.sachinnimbal.crudx.service.impl.sql;

import io.github.sachinnimbal.crudx.core.exception.DuplicateEntityException;
import io.github.sachinnimbal.crudx.core.model.CrudXBaseEntity;
import io.github.sachinnimbal.crudx.core.response.BatchResult;
import io.github.sachinnimbal.crudx.service.impl.sql.helper.CrudXSQLValidationHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.Serializable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
@RequiredArgsConstructor
public class CrudXSQLBatchOperations<T extends CrudXBaseEntity<ID>, ID extends Serializable> {

    private final EntityManager entityManager;
    private final CrudXSQLValidationHelper<T, ID> validationHelper;

    // Performance tuning constants
    private static final int SMALL_BATCH = 500;      // <= 1K records
    private static final int MEDIUM_BATCH = 1000;    // <= 10K records
    private static final int LARGE_BATCH = 2000;     // <= 50K records
    private static final int XLARGE_BATCH = 5000;    // <= 100K records
    private static final int MAX_BATCH = 10000;      // > 100K records

    private static final int FLUSH_INTERVAL = 100;   // Flush every N inserts
    private static final int MEMORY_CHECK_INTERVAL = 50; // GC check interval
    private static final long PROCESSING_TIMEOUT_MS = 300000; // 5 minutes
    private static final int MAX_ERROR_SAMPLES = 1000;

    /**
     * Enterprise batch creation with:
     * - In-memory duplicate detection within batch
     * - Single database duplicate check per batch (not per record)
     * - Optimized memory usage with streaming
     * - Real-time progress tracking
     */
    @Transactional(timeout = 1800, isolation = Isolation.READ_COMMITTED, propagation = Propagation.REQUIRED)
    public BatchResult<T> createBatch(List<T> entities, boolean skipDuplicates, Class<T> entityClass) {
        long startTime = System.currentTimeMillis();
        int totalSize = entities.size();

        log.info("🚀 SQL Enterprise Batch: {} entities | Mode: {} | Isolation: READ_COMMITTED",
                totalSize, skipDuplicates ? "SKIP_DUPLICATES" : "ABORT_ON_ERROR");

        // Initialize tracking
        BatchMetrics metrics = new BatchMetrics();
        Set<String> inMemoryConstraintKeys = ConcurrentHashMap.newKeySet(totalSize);
        List<String> errorSamples = Collections.synchronizedList(new ArrayList<>(MAX_ERROR_SAMPLES));

        int batchSize = calculateOptimalBatchSize(totalSize);
        int totalBatches = (totalSize + batchSize - 1) / batchSize;
        long processingDeadline = startTime + PROCESSING_TIMEOUT_MS;

        try {
            // Process in optimized chunks
            for (int chunkStart = 0; chunkStart < totalSize; chunkStart += batchSize) {

                // Timeout protection
                if (System.currentTimeMillis() > processingDeadline) {
                    return buildTimeoutResult(metrics, startTime, errorSamples);
                }

                metrics.batchNumber++;
                int chunkEnd = Math.min(chunkStart + batchSize, totalSize);

                // Phase 1: Validation & In-Memory Duplicate Detection
                List<T> validEntities = validateAndDeduplicateChunk(
                        entities, chunkStart, chunkEnd, inMemoryConstraintKeys,
                        skipDuplicates, metrics, errorSamples, entityClass
                );

                // Phase 2: Database Duplicate Check (SINGLE QUERY FOR ENTIRE BATCH)
                if (!validEntities.isEmpty()) {
                    List<T> noDuplicates = filterDatabaseDuplicates(
                            validEntities, skipDuplicates, metrics, errorSamples, entityClass
                    );

                    // Phase 3: Bulk Database Insert
                    if (!noDuplicates.isEmpty()) {
                        insertBatch(noDuplicates, skipDuplicates, metrics, errorSamples);
                    }
                }

                // Memory management
                clearProcessedEntities(entities, chunkStart, chunkEnd);

                // Progress logging
                if (metrics.batchNumber % 10 == 0 || metrics.batchNumber == totalBatches) {
                    logProgress(totalSize, chunkEnd, metrics, startTime);
                }

                // Periodic GC
                if (metrics.batchNumber % MEMORY_CHECK_INTERVAL == 0) {
                    System.gc();
                }
            }

            return buildSuccessResult(metrics, startTime, errorSamples);

        } catch (Exception e) {
            log.error("❌ Critical batch error: {}", e.getMessage(), e);
            if (!skipDuplicates) {
                throw new RuntimeException("Batch creation failed: " + e.getMessage(), e);
            }
            return buildErrorResult(metrics, startTime, errorSamples, e);
        } finally {
            entities.clear();
            inMemoryConstraintKeys.clear();
        }
    }

    /**
     * Phase 1: Validate and detect in-memory duplicates within the same batch
     */
    private List<T> validateAndDeduplicateChunk(
            List<T> entities, int start, int end,
            Set<String> inMemoryKeys, boolean skipDuplicates,
            BatchMetrics metrics, List<String> errors, Class<T> entityClass) {

        List<T> validEntities = new ArrayList<>(end - start);

        for (int i = start; i < end; i++) {
            T entity = entities.get(i);
            if (entity == null) continue;

            try {
                // Jakarta Bean Validation
                validationHelper.validateJakartaConstraints(entity);

                // Build constraint key for in-memory deduplication
                String constraintKey = validationHelper.buildConstraintKey(entity, entityClass);

                if (constraintKey != null && !constraintKey.isEmpty()) {
                    // Check in-memory duplicates (within same batch)
                    if (!inMemoryKeys.add(constraintKey)) {
                        metrics.duplicateCount.incrementAndGet();
                        metrics.skipCount.incrementAndGet();

                        if (errors.size() < MAX_ERROR_SAMPLES) {
                            errors.add(String.format("Index %d: In-batch duplicate - %s",
                                    i, validationHelper.buildDuplicateMessage(entity, entityClass)));
                        }

                        if (!skipDuplicates) {
                            throw new DuplicateEntityException(
                                    "Duplicate within batch at index " + i + ": " +
                                            validationHelper.buildDuplicateMessage(entity, entityClass));
                        }
                        continue;
                    }
                }

                validEntities.add(entity);

            } catch (DuplicateEntityException e) {
                throw e; // Propagate if abort mode
            } catch (Exception e) {
                metrics.validationFailCount.incrementAndGet();
                metrics.skipCount.incrementAndGet();

                if (errors.size() < MAX_ERROR_SAMPLES) {
                    errors.add(String.format("Index %d: Validation failed - %s", i, e.getMessage()));
                }

                if (!skipDuplicates) {
                    throw new IllegalArgumentException("Validation failed at index " + i, e);
                }
            }
        }

        return validEntities;
    }

    /**
     * Phase 2: Database duplicate check using SINGLE bulk query
     * This is the key optimization - check all entities in one database hit
     */
    private List<T> filterDatabaseDuplicates(
            List<T> entities, boolean skipDuplicates,
            BatchMetrics metrics, List<String> errors, Class<T> entityClass) {

        try {
            // SINGLE DATABASE QUERY for all entities
            Set<String> existingKeys = validationHelper.bulkCheckDuplicates(entities, entityClass, entityManager);

            if (existingKeys.isEmpty()) {
                return entities; // No duplicates, proceed with all
            }

            // Filter out duplicates
            List<T> noDuplicates = new ArrayList<>(entities.size());

            for (T entity : entities) {
                String key = validationHelper.buildConstraintKey(entity, entityClass);

                if (key != null && existingKeys.contains(key)) {
                    metrics.duplicateCount.incrementAndGet();
                    metrics.skipCount.incrementAndGet();

                    if (errors.size() < MAX_ERROR_SAMPLES) {
                        errors.add("DB duplicate: " + validationHelper.buildDuplicateMessage(entity, entityClass));
                    }

                    if (!skipDuplicates) {
                        throw new DuplicateEntityException(
                                "Database duplicate: " + validationHelper.buildDuplicateMessage(entity, entityClass));
                    }
                } else {
                    noDuplicates.add(entity);
                }
            }

            return noDuplicates;

        } catch (Exception e) {
            log.warn("Database duplicate check failed, proceeding with insert: {}", e.getMessage());
            return entities; // Proceed, let DB constraints handle it
        }
    }

    /**
     * Phase 3: Bulk database insert with optimized flushing
     */
    private void insertBatch(List<T> entities, boolean skipDuplicates,
                             BatchMetrics metrics, List<String> errors) {
        try {
            int insertCount = 0;

            for (T entity : entities) {
                entityManager.persist(entity);
                insertCount++;
                metrics.successCount.incrementAndGet();

                // Periodic flush to maintain memory
                if (insertCount % FLUSH_INTERVAL == 0) {
                    entityManager.flush();
                    entityManager.clear();
                }
            }

            // Final flush
            entityManager.flush();
            entityManager.clear();

        } catch (PersistenceException e) {
            metrics.dbErrorCount.incrementAndGet();

            if (errors.size() < MAX_ERROR_SAMPLES) {
                errors.add("DB constraint violation: " + extractRootCause(e));
            }

            if (!skipDuplicates) {
                throw new DuplicateEntityException("Database constraint violation: " + extractRootCause(e));
            }

            entityManager.clear();
            log.warn("Batch had DB constraint violations, skipped");
        }
    }

    /**
     * Calculate optimal batch size based on dataset size
     */
    private int calculateOptimalBatchSize(int totalSize) {
        if (totalSize <= 1000) return SMALL_BATCH;
        if (totalSize <= 10_000) return MEDIUM_BATCH;
        if (totalSize <= 50_000) return LARGE_BATCH;
        if (totalSize <= 100_000) return XLARGE_BATCH;
        return MAX_BATCH;
    }

    /**
     * Clear processed entities to free memory
     */
    private void clearProcessedEntities(List<T> entities, int start, int end) {
        for (int i = start; i < end; i++) {
            entities.set(i, null);
        }
    }

    /**
     * Real-time progress logging
     */
    private void logProgress(int total, int current, BatchMetrics metrics, long startTime) {
        long elapsed = System.currentTimeMillis() - startTime;
        double progress = (double) current / total * 100;
        double throughput = elapsed > 0 ? (metrics.successCount.get() * 1000.0) / elapsed : 0;
        long eta = elapsed > 0 ? (long) ((elapsed / progress) * (100 - progress)) : 0;

        long heapUsed = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) >> 20;
        long heapMax = Runtime.getRuntime().maxMemory() >> 20;

        log.info("📊 Progress: {}/{} ({:.1f}%) | Batch {}/{} | Success: {} | " +
                        "Skipped: {} (Dup: {}, Val: {}) | {:.0f} rec/sec | Mem: {}/{} MB | ETA: {} sec",
                current, total, progress, metrics.batchNumber,
                (total + calculateOptimalBatchSize(total) - 1) / calculateOptimalBatchSize(total),
                metrics.successCount.get(), metrics.skipCount.get(),
                metrics.duplicateCount.get(), metrics.validationFailCount.get(),
                throughput, heapUsed, heapMax, eta / 1000);
    }

    /**
     * Build success result
     */
    private BatchResult<T> buildSuccessResult(BatchMetrics metrics, long startTime, List<String> errors) {
        long duration = System.currentTimeMillis() - startTime;
        double throughput = duration > 0 ? (metrics.successCount.get() * 1000.0) / duration : 0.0;

        log.info("✅ SQL Batch Complete: {} success, {} skipped (duplicates: {}, validation: {}) | {:.0f} rec/sec | {} ms",
                metrics.successCount.get(), metrics.skipCount.get(),
                metrics.duplicateCount.get(), metrics.validationFailCount.get(),
                throughput, duration);

        BatchResult<T> result = new BatchResult<>();
        result.setCreatedEntities(Collections.emptyList());
        result.setSuccessCount(metrics.successCount.get());
        result.setSkippedCount(metrics.skipCount.get());
        result.setDuplicateSkipCount(metrics.duplicateCount.get());
        result.setSkippedReasons(errors.isEmpty() ? null : errors);

        return result;
    }

    private BatchResult<T> buildTimeoutResult(BatchMetrics metrics, long startTime, List<String> errors) {
        long duration = System.currentTimeMillis() - startTime;
        log.error("⏱️ Batch timeout after {} ms. Processed: {}", duration, metrics.successCount.get());
        return buildSuccessResult(metrics, startTime, errors);
    }

    private BatchResult<T> buildErrorResult(BatchMetrics metrics, long startTime, List<String> errors, Exception e) {
        errors.add("Critical error: " + e.getMessage());
        return buildSuccessResult(metrics, startTime, errors);
    }

    private String extractRootCause(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String msg = cause.getMessage();
        return msg != null && msg.length() > 100 ? msg.substring(0, 100) + "..." : msg;
    }

    /**
     * Batch metrics tracker
     */
    private static class BatchMetrics {
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger skipCount = new AtomicInteger(0);
        AtomicInteger duplicateCount = new AtomicInteger(0);
        AtomicInteger validationFailCount = new AtomicInteger(0);
        AtomicInteger dbErrorCount = new AtomicInteger(0);
        int batchNumber = 0;
    }
}