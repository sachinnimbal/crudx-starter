package io.github.sachinnimbal.crudx.service.bulkImpl;

import io.github.sachinnimbal.crudx.core.performance.CrudXBatchMetricsTracker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.ArrayList;
import java.util.List;

@Slf4j
public class CrudXMongoBulkOperations {

    private static final int DEFAULT_BATCH_SIZE = 1000;
    private static final int GC_CLEANUP_INTERVAL = 50; // More aggressive

    public static <T> int bulkInsert(
            List<T> entities,
            MongoTemplate mongoTemplate,
            Class<T> entityClass,
            boolean skipDuplicates,
            CrudXBatchMetricsTracker metricsTracker) {

        if (entities == null || entities.isEmpty()) {
            return 0;
        }

        int totalSize = entities.size();
        int batchSize = DEFAULT_BATCH_SIZE;
        int totalBatches = (totalSize + batchSize - 1) / batchSize;
        int successCount = 0;

        log.info("🔥 MongoDB Bulk Insert: {} entities in {} batches (batch size: {})",
                totalSize, totalBatches, batchSize);

        for (int batchNum = 1; batchNum <= totalBatches; batchNum++) {
            int startIdx = (batchNum - 1) * batchSize;
            int endIdx = Math.min(startIdx + batchSize, totalSize);

            try {
                List<T> batchView = entities.subList(startIdx, endIdx);

                List<T> batchEntities = new ArrayList<>(batchView.size());
                for (T entity : batchView) {
                    if (entity != null) {
                        batchEntities.add(entity);
                    }
                }

                if (!batchEntities.isEmpty()) {
                    long dbStartTime = System.currentTimeMillis();

                    BulkOperations bulkOps = mongoTemplate.bulkOps(
                            BulkOperations.BulkMode.UNORDERED, entityClass);

                    bulkOps.insert(batchEntities);
                    com.mongodb.bulk.BulkWriteResult result = bulkOps.execute();

                    long dbTime = System.currentTimeMillis() - dbStartTime;

                    int insertedCount = result.getInsertedCount();
                    successCount += insertedCount;

                    if (metricsTracker != null) {
                        metricsTracker.addDbWriteTime(dbTime);

                        if (batchNum == 1 && !batchEntities.isEmpty()) {
                            T firstEntity = batchEntities.get(0);
                            String id = extractEntityId(firstEntity);
                            metricsTracker.addObjectTiming(id, 0, 0, dbTime / batchEntities.size());
                        }
                    }
                }

                for (int i = startIdx; i < endIdx; i++) {
                    entities.set(i, null);
                }

                batchEntities.clear();

            } catch (Exception e) {
                if (!skipDuplicates) {
                    log.error("MongoDB bulk insert failed at batch {}/{}: {}",
                            batchNum, totalBatches, e.getMessage());
                    throw e;
                }

                log.warn("Batch {}/{} failed, attempting one-by-one insert",
                        batchNum, totalBatches);

                for (int i = startIdx; i < endIdx; i++) {
                    try {
                        T entity = entities.get(i);
                        if (entity != null) {
                            mongoTemplate.save(entity);
                            successCount++;
                        }
                    } catch (Exception ex) {
                        if (metricsTracker != null) {
                            metricsTracker.incrementFailed();
                        }
                    } finally {
                        entities.set(i, null);
                    }
                }
            }

            if (batchNum % GC_CLEANUP_INTERVAL == 0) {
                System.gc();
            }

            // Progress logging
            if (batchNum % 20 == 0 || batchNum == totalBatches) {
                long currentMemory = (Runtime.getRuntime().totalMemory() -
                        Runtime.getRuntime().freeMemory()) / 1024 / 1024;

                log.info("📊 Progress: {}/{} batches | Success: {} | Memory: {} MB",
                        batchNum, totalBatches, successCount, currentMemory);
            }
        }
        entities.clear();

        return successCount;
    }

    private static <T> String extractEntityId(T entity) {
        try {
            var idField = entity.getClass().getDeclaredField("id");
            idField.setAccessible(true);
            Object id = idField.get(entity);
            return id != null ? id.toString() : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }
}