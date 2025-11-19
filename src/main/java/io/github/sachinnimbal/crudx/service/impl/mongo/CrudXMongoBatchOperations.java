package io.github.sachinnimbal.crudx.service.impl.mongo;

import io.github.sachinnimbal.crudx.core.exception.DuplicateEntityException;
import io.github.sachinnimbal.crudx.core.model.CrudXMongoEntity;
import io.github.sachinnimbal.crudx.core.response.BatchResult;
import io.github.sachinnimbal.crudx.service.impl.mongo.helper.CrudXMongoValidationHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.Serializable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
@RequiredArgsConstructor
public class CrudXMongoBatchOperations<T extends CrudXMongoEntity<ID>, ID extends Serializable> {

    private final MongoTemplate mongoTemplate;
    private final CrudXMongoValidationHelper<T, ID> validationHelper;

    // Performance tuning
    private static final int SMALL_BATCH = 500;
    private static final int MEDIUM_BATCH = 1000;
    private static final int LARGE_BATCH = 2000;
    private static final int XLARGE_BATCH = 5000;
    private static final int MAX_BATCH = 10000;

    private static final int MEMORY_CHECK_INTERVAL = 50;
    private static final long PROCESSING_TIMEOUT_MS = 300000; // 5 minutes
    private static final int MAX_ERROR_SAMPLES = 1000;

    @Transactional(timeout = 1800)
    public BatchResult<T> createBatch(List<T> entities, boolean skipDuplicates, Class<T> entityClass) {
        long startTime = System.currentTimeMillis();
        int totalSize = entities.size();

        log.info("🚀 MongoDB Enterprise Batch: {} entities | Mode: {} | Optimized bulk operations",
                totalSize, skipDuplicates ? "SKIP_DUPLICATES" : "ABORT_ON_ERROR");

        BatchMetrics metrics = new BatchMetrics();
        Set<String> inMemoryConstraintKeys = ConcurrentHashMap.newKeySet(totalSize);
        List<String> errorSamples = Collections.synchronizedList(new ArrayList<>(MAX_ERROR_SAMPLES));

        int batchSize = calculateOptimalBatchSize(totalSize);
        int totalBatches = (totalSize + batchSize - 1) / batchSize;
        long processingDeadline = startTime + PROCESSING_TIMEOUT_MS;

        try {
            for (int chunkStart = 0; chunkStart < totalSize; chunkStart += batchSize) {

                if (System.currentTimeMillis() > processingDeadline) {
                    return buildTimeoutResult(metrics, startTime, errorSamples);
                }

                metrics.batchNumber++;
                int chunkEnd = Math.min(chunkStart + batchSize, totalSize);

                // Phase 1: Validate & In-Memory Dedup
                List<T> validEntities = validateAndDeduplicateChunk(
                        entities, chunkStart, chunkEnd, inMemoryConstraintKeys,
                        skipDuplicates, metrics, errorSamples, entityClass
                );

                // Phase 2: Single Database Duplicate Check for Entire Batch
                if (!validEntities.isEmpty()) {
                    List<T> noDuplicates = filterDatabaseDuplicates(
                            validEntities, skipDuplicates, metrics, errorSamples, entityClass
                    );

                    // Phase 3: Bulk Insert with MongoDB BulkOperations
                    if (!noDuplicates.isEmpty()) {
                        insertBulk(noDuplicates, skipDuplicates, metrics, errorSamples, entityClass);
                    }
                }

                clearProcessedEntities(entities, chunkStart, chunkEnd);

                if (metrics.batchNumber % 10 == 0 || metrics.batchNumber == totalBatches) {
                    logProgress(totalSize, chunkEnd, metrics, startTime);
                }

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
     * Phase 1: Validation and in-memory deduplication
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
                validationHelper.validateJakartaConstraints(entity);

                String constraintKey = validationHelper.buildConstraintKey(entity, entityClass);

                if (constraintKey != null && !constraintKey.isEmpty()) {
                    if (!inMemoryKeys.add(constraintKey)) {
                        metrics.duplicateCount.incrementAndGet();
                        metrics.skipCount.incrementAndGet();

                        if (errors.size() < MAX_ERROR_SAMPLES) {
                            errors.add(String.format("Index %d: In-batch duplicate - %s",
                                    i, validationHelper.buildDuplicateMessage(entity, entityClass)));
                        }

                        if (!skipDuplicates) {
                            throw new DuplicateEntityException(
                                    "Duplicate within batch at index " + i);
                        }
                        continue;
                    }
                }

                entity.onCreate();
                validEntities.add(entity);

            } catch (DuplicateEntityException e) {
                throw e;
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
     * Phase 2: SINGLE database query to check ALL duplicates
     * This is the key optimization - 1 DB hit instead of N
     */
    private List<T> filterDatabaseDuplicates(
            List<T> entities, boolean skipDuplicates,
            BatchMetrics metrics, List<String> errors, Class<T> entityClass) {

        try {
            // SINGLE BULK DATABASE QUERY
            Set<String> existingKeys = validationHelper.bulkCheckDuplicates(
                    entities, entityClass, mongoTemplate
            );

            if (existingKeys.isEmpty()) {
                return entities;
            }

            List<T> noDuplicates = new ArrayList<>(entities.size());

            for (T entity : entities) {
                String key = validationHelper.buildConstraintKey(entity, entityClass);

                if (key != null && existingKeys.contains(key)) {
                    metrics.duplicateCount.incrementAndGet();
                    metrics.skipCount.incrementAndGet();

                    if (errors.size() < MAX_ERROR_SAMPLES) {
                        errors.add("DB duplicate: " +
                                validationHelper.buildDuplicateMessage(entity, entityClass));
                    }

                    if (!skipDuplicates) {
                        throw new DuplicateEntityException("Database duplicate detected");
                    }
                } else {
                    noDuplicates.add(entity);
                }
            }

            return noDuplicates;

        } catch (Exception e) {
            log.warn("Database duplicate check failed, proceeding: {}", e.getMessage());
            return entities;
        }
    }

    /**
     * Phase 3: MongoDB bulk insert with unordered operations for speed
     */
    private void insertBulk(List<T> entities, boolean skipDuplicates,
                            BatchMetrics metrics, List<String> errors, Class<T> entityClass) {
        try {
            BulkOperations bulkOps = mongoTemplate.bulkOps(
                    skipDuplicates ? BulkOperations.BulkMode.UNORDERED : BulkOperations.BulkMode.ORDERED,
                    entityClass
            );

            bulkOps.insert(entities);
            com.mongodb.bulk.BulkWriteResult result = bulkOps.execute();

            int inserted = result.getInsertedCount();
            metrics.successCount.addAndGet(inserted);

            // MongoDB might still skip some due to race conditions
            int dbSkipped = entities.size() - inserted;
            if (dbSkipped > 0) {
                metrics.skipCount.addAndGet(dbSkipped);
                metrics.duplicateCount.addAndGet(dbSkipped);

                if (errors.size() < MAX_ERROR_SAMPLES) {
                    errors.add(String.format("Batch %d: %d MongoDB duplicate key errors",
                            metrics.batchNumber, dbSkipped));
                }
            }

        } catch (org.springframework.dao.DuplicateKeyException e) {
            metrics.dbErrorCount.incrementAndGet();

            if (errors.size() < MAX_ERROR_SAMPLES) {
                errors.add("DB constraint violation: " + extractRootCause(e));
            }

            if (!skipDuplicates) {
                throw new DuplicateEntityException("Database constraint violation");
            }

            log.warn("Batch {} had DB constraint violations", metrics.batchNumber);
        }
    }

    private int calculateOptimalBatchSize(int totalSize) {
        if (totalSize <= 1000) return SMALL_BATCH;
        if (totalSize <= 10_000) return MEDIUM_BATCH;
        if (totalSize <= 50_000) return LARGE_BATCH;
        if (totalSize <= 100_000) return XLARGE_BATCH;
        return MAX_BATCH;
    }

    private void clearProcessedEntities(List<T> entities, int start, int end) {
        for (int i = start; i < end; i++) {
            entities.set(i, null);
        }
    }

    private void logProgress(int total, int current, BatchMetrics metrics, long startTime) {
        long elapsed = System.currentTimeMillis() - startTime;
        double progress = (double) current / total * 100;
        double throughput = elapsed > 0 ? (metrics.successCount.get() * 1000.0) / elapsed : 0;
        long eta = elapsed > 0 ? (long) ((elapsed / progress) * (100 - progress)) : 0;

        long heapUsed = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) >> 20;
        long heapMax = Runtime.getRuntime().maxMemory() >> 20;

        log.info("📊 MongoDB Progress: {}/{} ({:.1f}%) | Success: {} | Skipped: {} " +
                        "(Dup: {}, Val: {}) | {:.0f} rec/sec | Mem: {}/{} MB | ETA: {} sec",
                current, total, progress, metrics.successCount.get(), metrics.skipCount.get(),
                metrics.duplicateCount.get(), metrics.validationFailCount.get(),
                throughput, heapUsed, heapMax, eta / 1000);
    }

    private BatchResult<T> buildSuccessResult(BatchMetrics metrics, long startTime, List<String> errors) {
        long duration = System.currentTimeMillis() - startTime;
        double throughput = duration > 0 ? (metrics.successCount.get() * 1000.0) / duration : 0.0;

        log.info("✅ MongoDB Batch Complete: {} success, {} skipped (duplicates: {}, validation: {}) | {:.0f} rec/sec | {} ms",
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

    private static class BatchMetrics {
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger skipCount = new AtomicInteger(0);
        AtomicInteger duplicateCount = new AtomicInteger(0);
        AtomicInteger validationFailCount = new AtomicInteger(0);
        AtomicInteger dbErrorCount = new AtomicInteger(0);
        int batchNumber = 0;
    }
}