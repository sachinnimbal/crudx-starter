package io.github.sachinnimbal.crudx.web.components;

import io.github.sachinnimbal.crudx.core.enums.CrudXOperation;
import io.github.sachinnimbal.crudx.core.model.CrudXBaseEntity;
import io.github.sachinnimbal.crudx.core.response.BatchResult;
import io.github.sachinnimbal.crudx.service.CrudXService;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Handles all batch processing logic for CrudXController
 */
@Slf4j
public class CrudXBatchProcessor<T extends CrudXBaseEntity<ID>, ID extends Serializable> {

    private static final int LARGE_DATASET_THRESHOLD = 1000;

    private final CrudXService<T, ID> crudService;
    private final CrudXDTOConverter<T, ID> dtoConverter;
    private final CrudXValidationHelper<T, ID> validationHelper;

    // Lifecycle hooks
    private final LifecycleCallbacks<T, ID> lifecycleCallbacks;

    public CrudXBatchProcessor(
            CrudXService<T, ID> crudService,
            CrudXDTOConverter<T, ID> dtoConverter,
            CrudXValidationHelper<T, ID> validationHelper,
            LifecycleCallbacks<T, ID> lifecycleCallbacks) {

        this.crudService = crudService;
        this.dtoConverter = dtoConverter;
        this.validationHelper = validationHelper;
        this.lifecycleCallbacks = lifecycleCallbacks;
    }

    /**
     * Process batch creation with intelligent chunking and memory management
     */
    public BatchCreationResult processBatchCreation(
            List<Map<String, Object>> requestBodies,
            boolean skipDuplicates,
            int batchSize) {

        long startTime = System.currentTimeMillis();
        int totalSize = requestBodies.size();

        int successCount = 0;
        int skipCount = 0;
        List<String> skipReasons = new ArrayList<>();
        int dbHits = 0;

        long maxProcessingTime = 300000; // 5 minutes max
        long processingDeadline = startTime + maxProcessingTime;

        int dbBatchSize = calculateOptimalBatchSize(totalSize);
        int conversionBatchSize = Math.min(200, dbBatchSize / 5);

        for (int chunkStart = 0; chunkStart < totalSize; chunkStart += dbBatchSize) {

            // Timeout check
            if (System.currentTimeMillis() > processingDeadline) {
                return buildTimeoutResult(totalSize, successCount, skipCount, dbHits,
                        startTime, skipReasons);
            }

            int chunkEnd = Math.min(chunkStart + dbBatchSize, totalSize);
            List<T> chunkEntities = new ArrayList<>(chunkEnd - chunkStart);

            // Conversion phase
            for (int i = chunkStart; i < chunkEnd; i += conversionBatchSize) {
                int batchEnd = Math.min(i + conversionBatchSize, chunkEnd);

                for (int j = i; j < batchEnd; j++) {
                    try {
                        Map<String, Object> record = requestBodies.get(j);

                        if (record == null || record.isEmpty()) {
                            skipCount++;
                            if (skipReasons.size() < 1000) {
                                skipReasons.add(String.format("Index %d: Empty or null record", j));
                            }
                            continue;
                        }

                        T entity = dtoConverter.convertMapToEntity(record, CrudXOperation.BATCH_CREATE);
                        validationHelper.validateRequiredFields(entity);
                        chunkEntities.add(entity);

                    } catch (Exception e) {
                        skipCount++;
                        if (skipReasons.size() < 1000) {
                            skipReasons.add(String.format("Index %d: %s", j, e.getMessage()));
                        }
                        log.debug("Skipped record {}: {}", j, e.getMessage());
                    }

                    requestBodies.set(j, null); // Free memory
                }
            }

            // Database insert phase
            if (!chunkEntities.isEmpty()) {
                try {
                    lifecycleCallbacks.beforeCreateBatch(chunkEntities);
                    BatchResult<T> result = crudService.createBatch(chunkEntities, skipDuplicates);
                    lifecycleCallbacks.afterCreateBatch(result.getCreatedEntities());

                    int inserted = chunkEntities.size() - result.getSkippedCount();
                    successCount += inserted;
                    skipCount += result.getSkippedCount();
                    dbHits++;

                    if (result.getSkippedReasons() != null) {
                        skipReasons.addAll(result.getSkippedReasons());
                    }

                    log.debug("Chunk {}: {} inserted, {} skipped",
                            (chunkStart / dbBatchSize) + 1, inserted, result.getSkippedCount());

                } catch (Exception e) {
                    log.error("❌ Chunk {} failed: {}",
                            (chunkStart / dbBatchSize) + 1, e.getMessage());

                    if (!skipDuplicates) {
                        requestBodies.clear();
                        throw new RuntimeException(
                                String.format("Batch failed at chunk %d: %s",
                                        (chunkStart / dbBatchSize) + 1, e.getMessage()),
                                e
                        );
                    }
                    skipCount += chunkEntities.size();
                }
            }

            chunkEntities.clear();

            // Progress logging
            if ((chunkStart / dbBatchSize) % 5 == 0 || chunkEnd == totalSize) {
                logRealtimeProgress(totalSize, chunkEnd, successCount, skipCount, startTime);
            }

            // Memory management
            if ((chunkStart / dbBatchSize) % 50 == 0) {
                System.gc();
            }
        }

        requestBodies.clear();

        long duration = System.currentTimeMillis() - startTime;
        double recordsPerSecond = duration > 0 ? (successCount * 1000.0) / duration : 0.0;

        return new BatchCreationResult(
                totalSize, successCount, skipCount, dbHits,
                duration, recordsPerSecond, skipReasons, false
        );
    }

    /**
     * Process batch updates
     */
    public BatchResult<T> processBatchUpdate(Map<ID, Map<String, Object>> updates) {
        return crudService.updateBatch(updates);
    }

    /**
     * Process batch deletion
     */
    public BatchResult<T> processBatchDelete(List<ID> ids) {
        lifecycleCallbacks.beforeDeleteBatch(ids);
        BatchResult<T> result = crudService.deleteBatch(ids);

        List<ID> deletedIds = result.getCreatedEntities().stream()
                .map(T::getId)
                .toList();

        lifecycleCallbacks.afterDeleteBatch(deletedIds);

        return result;
    }

    /**
     * Process force batch deletion
     */
    public int processForceDelete(List<ID> ids, int batchSize) {
        lifecycleCallbacks.beforeDeleteBatch(ids);

        int totalDeleted = 0;
        List<ID> actuallyDeletedIds = new ArrayList<>();

        for (int i = 0; i < ids.size(); i += batchSize) {
            int end = Math.min(i + batchSize, ids.size());
            List<ID> batchIds = new ArrayList<>(ids.subList(i, end));

            crudService.deleteBatch(batchIds);
            totalDeleted += batchIds.size();
            actuallyDeletedIds.addAll(batchIds);
            batchIds.clear();

            log.debug("Force deleted {}/{} entities", totalDeleted, ids.size());
        }

        lifecycleCallbacks.afterDeleteBatch(actuallyDeletedIds);
        return totalDeleted;
    }

    /**
     * Build comprehensive batch response data
     */
    public Map<String, Object> buildBatchResponseData(BatchCreationResult result) {
        Map<String, Object> responseData = new LinkedHashMap<>();
        responseData.put("totalProcessed", result.getTotalProcessed());
        responseData.put("successCount", result.getSuccessCount());
        responseData.put("skipCount", result.getSkipCount());
        responseData.put("databaseHits", result.getDatabaseHits());
        responseData.put("recordsPerSecond", (int) result.getRecordsPerSecond());
        responseData.put("executionTimeMs", result.getDuration());

        // Memory metrics
        long finalMemory = (Runtime.getRuntime().totalMemory() -
                Runtime.getRuntime().freeMemory()) / 1024 / 1024;
        responseData.put("finalMemoryMB", finalMemory);

        // Performance rating
        String performanceRating = calculatePerformanceRating(result.getRecordsPerSecond());
        responseData.put("performanceRating", performanceRating);

        // Error details (first 10 only)
        if (!result.getSkipReasons().isEmpty()) {
            responseData.put("errorSample",
                    result.getSkipReasons().subList(0, Math.min(10, result.getSkipReasons().size())));
            if (result.getSkipReasons().size() > 10) {
                responseData.put("errorNote",
                        String.format("Showing first 10 of %d errors", result.getSkipReasons().size()));
            }
        }

        if (result.isTimeout()) {
            responseData.put("status", "PARTIAL_SUCCESS");
            responseData.put("timeoutError",
                    String.format("Batch processing timeout. Processed %d/%d records.",
                            result.getSuccessCount(), result.getTotalProcessed()));
        }

        return responseData;
    }

    // ==================== PRIVATE HELPER METHODS ====================

    private int calculateOptimalBatchSize(int totalSize) {
        if (totalSize <= 1000) return Math.min(500, totalSize);
        if (totalSize <= 10_000) return 1000;
        if (totalSize <= 50_000) return 2000;
        if (totalSize <= 100_000) return 5000;
        return 10_000;
    }

    private void logRealtimeProgress(int total, int current, int success, int skipped, long startTime) {
        double progress = (double) current / total * 100;
        long elapsed = System.currentTimeMillis() - startTime;
        long estimated = elapsed > 0 ? (long) ((elapsed / progress) * 100) : 0;
        long remaining = estimated - elapsed;

        long currentMemory = (Runtime.getRuntime().totalMemory() -
                Runtime.getRuntime().freeMemory()) / 1024 / 1024;
        long maxMemory = Runtime.getRuntime().maxMemory() / 1024 / 1024;
        double memoryUsage = (double) currentMemory / maxMemory * 100;

        double speed = elapsed > 0 ? (success * 1000.0) / elapsed : 0;

        log.info("📊 Progress: {}/{} ({:.1f}%) | Success: {} | Skipped: {} | Speed: {} rec/sec | " +
                        "Memory: {} MB / {} MB ({:.1f}%) | Elapsed: {} ms | ETA: {} ms",
                current, total, progress, success, skipped, (int) speed,
                currentMemory, maxMemory, memoryUsage, elapsed, remaining);
    }

    private String calculatePerformanceRating(double recordsPerSecond) {
        if (recordsPerSecond > 5000) return "EXCELLENT";
        if (recordsPerSecond > 2000) return "GOOD";
        if (recordsPerSecond > 1000) return "MODERATE";
        return "SLOW";
    }

    private BatchCreationResult buildTimeoutResult(
            int totalSize, int successCount, int skipCount,
            int dbHits, long startTime, List<String> skipReasons) {

        long duration = System.currentTimeMillis() - startTime;
        double recordsPerSecond = duration > 0 ? (successCount * 1000.0) / duration : 0.0;

        String timeoutMsg = String.format(
                "Batch processing timeout after 300000 ms. Processed %d/%d records successfully.",
                successCount, totalSize
        );
        log.error(timeoutMsg);

        return new BatchCreationResult(
                totalSize, successCount, skipCount, dbHits,
                duration, recordsPerSecond, skipReasons, true
        );
    }

    // ==================== RESULT CLASS ====================

    @Data
    @AllArgsConstructor
    public static class BatchCreationResult {
        private int totalProcessed;
        private int successCount;
        private int skipCount;
        private int databaseHits;
        private long duration;
        private double recordsPerSecond;
        private List<String> skipReasons;
        private boolean timeout;
    }

    // ==================== LIFECYCLE CALLBACKS INTERFACE ====================

    public interface LifecycleCallbacks<T extends CrudXBaseEntity<ID>, ID extends Serializable> {
        void beforeCreateBatch(List<T> entities);

        void afterCreateBatch(List<T> entities);

        void beforeDeleteBatch(List<ID> ids);

        void afterDeleteBatch(List<ID> deletedIds);
    }
}