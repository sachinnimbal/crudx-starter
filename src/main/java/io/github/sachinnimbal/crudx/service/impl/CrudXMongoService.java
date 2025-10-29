package io.github.sachinnimbal.crudx.service.impl;

import io.github.sachinnimbal.crudx.core.annotations.CrudXUniqueConstraint;
import io.github.sachinnimbal.crudx.core.config.CrudXProperties;
import io.github.sachinnimbal.crudx.core.exception.DuplicateEntityException;
import io.github.sachinnimbal.crudx.core.exception.EntityNotFoundException;
import io.github.sachinnimbal.crudx.core.model.CrudXMongoEntity;
import io.github.sachinnimbal.crudx.core.response.BatchResult;
import io.github.sachinnimbal.crudx.service.CrudXService;
import jakarta.annotation.PostConstruct;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.GenericTypeResolver;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.transaction.annotation.Transactional;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 🚀 ENTERPRISE MongoDB Service
 * <p>
 * BULK INSERT STRATEGY:
 * - UNORDERED bulk operations (continue on error)
 * - Zero pre-validation DB queries
 * - Adaptive batch sizing (MongoDB optimal: 1000-2000)
 * - Streaming processing (memory-bounded)
 * - Real-time progress tracking
 */
@Slf4j
public abstract class CrudXMongoService<T extends CrudXMongoEntity<ID>, ID extends Serializable>
        implements CrudXService<T, ID> {

    @Autowired
    protected MongoTemplate mongoTemplate;

    @Autowired(required = false)
    protected Validator validator;

    protected Class<T> entityClass;

    @Autowired
    protected CrudXProperties crudxProperties;

    private static final int MAX_IN_MEMORY_THRESHOLD = 5000;

    // 🔥 MongoDB-optimized batch sizes
    private static final int BATCH_SIZE_SMALL = 100;
    private static final int BATCH_SIZE_MEDIUM = 500;
    private static final int BATCH_SIZE_LARGE = 1000;
    private static final int BATCH_SIZE_MAX = 2000; // MongoDB recommended max

    // 🔥 Unique constraint cache
    private static final Map<Class<?>, List<UniqueConstraintMeta>> UNIQUE_CONSTRAINT_CACHE =
            new ConcurrentHashMap<>();

    @PostConstruct
    @SuppressWarnings("unchecked")
    protected void init() {
        if (mongoTemplate == null) {
            throw new IllegalStateException(
                    "MongoTemplate not available. Add 'spring-boot-starter-data-mongodb' dependency.");
        }

        Type genericSuperclass = getClass().getGenericSuperclass();
        if (genericSuperclass instanceof ParameterizedType paramType) {
            Type[] typeArgs = paramType.getActualTypeArguments();
            if (typeArgs.length > 0 && typeArgs[0] instanceof Class) {
                entityClass = (Class<T>) typeArgs[0];
                cacheUniqueConstraints();
                validateMongoConnection();
                return;
            }
        }

        Class<?>[] typeArgs = GenericTypeResolver.resolveTypeArguments(getClass(), CrudXMongoService.class);
        if (typeArgs != null && typeArgs.length > 0) {
            entityClass = (Class<T>) typeArgs[0];
            cacheUniqueConstraints();
            validateMongoConnection();
            return;
        }

        throw new IllegalStateException("Could not resolve entity class: " + getClass().getSimpleName());
    }

    private void validateMongoConnection() {
        try {
            mongoTemplate.executeCommand("{ ping: 1 }");
            log.debug("MongoDB connection validated for {}", entityClass.getSimpleName());
        } catch (Exception e) {
            log.error("MongoDB connection failed", e);
            throw new IllegalStateException("Cannot connect to MongoDB", e);
        }
    }

    @Override
    public T create(T entity) {
        validateUniqueConstraints(entity);
        entity.onCreate();
        return mongoTemplate.save(entity);
    }

    /**
     * 🚀 ENTERPRISE BULK INSERT - MongoDB Optimized
     * <p>
     * STRATEGY:
     * 1. UNORDERED bulk ops (continue on duplicate key errors)
     * 2. Zero pre-validation queries (let MongoDB handle duplicates)
     * 3. Adaptive batch sizing for optimal throughput
     * 4. Streaming with immediate memory release
     * 5. Real-time progress tracking
     */
    @Override
    @Transactional(timeout = 1800)
    public BatchResult<T> createBatch(List<T> entities, boolean skipDuplicates) {
        long startTime = System.currentTimeMillis();
        int totalSize = entities.size();

        log.info("🚀 MongoDB Bulk: {} entities | Mode: {}",
                totalSize, skipDuplicates ? "SKIP_ERRORS" : "ABORT_ON_ERROR");

        // 🔥 ADAPTIVE batch sizing for MongoDB
        int batchSize = calculateMongoBatchSize(totalSize);

        int successCount = 0;
        int skipCount = 0;
        int batchNumber = 0;
        int totalBatches = (totalSize + batchSize - 1) / batchSize;

        List<String> skipReasons = new ArrayList<>(Math.min(1000, totalSize / 10));

        // 🔥 STREAMING: Process in chunks
        for (int i = 0; i < totalSize; i += batchSize) {
            batchNumber++;
            int end = Math.min(i + batchSize, totalSize);

            // 🔥 Prepare batch (in-memory validation only)
            List<T> validEntities = new ArrayList<>(end - i);

            for (int j = i; j < end; j++) {
                T entity = entities.get(j);
                if (entity == null) continue;

                try {
                    // 🔥 IN-MEMORY validation only
                    validateInMemory(entity);
                    entity.onCreate();
                    validEntities.add(entity);
                } catch (Exception e) {
                    if (skipDuplicates) {
                        skipCount++;
                        if (skipReasons.size() < 1000) {
                            skipReasons.add(String.format("Index %d: %s", j, e.getMessage()));
                        }
                    } else {
                        log.error("Validation failed at index {}: {}", j, e.getMessage());
                        throw new IllegalArgumentException("Validation failed at index " + j, e);
                    }
                }

                // 🔥 Free memory immediately
                entities.set(j, null);
            }

            // 🔥 BULK INSERT via MongoDB BulkOperations
            if (!validEntities.isEmpty()) {
                try {
                    BulkOperations bulkOps = mongoTemplate.bulkOps(
                            skipDuplicates ? BulkOperations.BulkMode.UNORDERED : BulkOperations.BulkMode.ORDERED,
                            entityClass);

                    bulkOps.insert(validEntities);

                    com.mongodb.bulk.BulkWriteResult result = bulkOps.execute();
                    int inserted = result.getInsertedCount();
                    successCount += inserted;

                    // 🔥 Calculate skipped (validation passed but DB rejected)
                    int dbSkipped = validEntities.size() - inserted;
                    if (dbSkipped > 0) {
                        skipCount += dbSkipped;
                        if (skipReasons.size() < 1000) {
                            skipReasons.add(String.format("Batch %d: %d duplicate key errors",
                                    batchNumber, dbSkipped));
                        }
                    }

                    log.debug("✅ Batch {}/{}: {} inserted, {} skipped",
                            batchNumber, totalBatches, inserted, dbSkipped);

                } catch (org.springframework.dao.DuplicateKeyException e) {
                    if (skipDuplicates) {
                        // 🔥 Fallback: Individual inserts to identify duplicates
                        int individualSuccess = 0;
                        int individualSkip = 0;

                        for (T entity : validEntities) {
                            try {
                                mongoTemplate.save(entity);
                                individualSuccess++;
                            } catch (org.springframework.dao.DuplicateKeyException dupEx) {
                                individualSkip++;
                                if (skipReasons.size() < 1000) {
                                    skipReasons.add("Duplicate key: " + extractDuplicateKey(dupEx));
                                }
                            }
                        }

                        successCount += individualSuccess;
                        skipCount += individualSkip;

                        log.debug("⚠️ Batch {}/{} fallback: {} success, {} duplicates",
                                batchNumber, totalBatches, individualSuccess, individualSkip);
                    } else {
                        log.error("Batch {} aborted: {}", batchNumber, e.getMessage());
                        throw new DuplicateEntityException("Duplicate key in batch " + batchNumber);
                    }
                } catch (Exception e) {
                    log.error("Batch {} failed: {}", batchNumber, e.getMessage());
                    if (!skipDuplicates) throw e;

                    skipCount += validEntities.size();
                    if (skipReasons.size() < 1000) {
                        skipReasons.add(String.format("Batch %d failed: %s", batchNumber, e.getMessage()));
                    }
                }
            }

            validEntities.clear();

            // 🔥 PROGRESS logging
            if (batchNumber % 10 == 0 || batchNumber == totalBatches) {
                logProgress(totalSize, end, successCount, skipCount, startTime, batchNumber, totalBatches);
            }

            // 🔥 GC hint for large datasets
            if (batchNumber % 100 == 0 && totalSize > 50_000) {
                System.gc();
            }
        }

        // Clear input list
        entities.clear();

        long duration = System.currentTimeMillis() - startTime;
        double throughput = duration > 0 ? (successCount * 1000.0) / duration : 0.0;

        String throughputStr = String.format("%.0f", throughput);
        log.info("✅ MongoDB Bulk Complete: {} success, {} skipped | {} rec/sec | {} ms",
                successCount, skipCount, throughputStr, duration);

        BatchResult<T> result = new BatchResult<>();
        result.setCreatedEntities(Collections.emptyList());
        result.setSuccessCount(successCount);
        result.setSkippedCount(skipCount);
        result.setSkippedReasons(skipReasons.isEmpty() ? null : skipReasons);

        return result;
    }

    /**
     * 🔥 IN-MEMORY validation (no DB queries)
     */
    private void validateInMemory(T entity) {
        if (validator != null) {
            Set<ConstraintViolation<T>> violations = validator.validate(entity);
            if (!violations.isEmpty()) {
                String errors = violations.stream()
                        .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                        .collect(Collectors.joining(", "));
                throw new IllegalArgumentException("Validation failed: " + errors);
            }
        }
        // Unique constraints validated by MongoDB on INSERT
    }

    /**
     * 🔥 MongoDB-optimized batch sizing
     */
    private int calculateMongoBatchSize(int totalSize) {
        if (totalSize <= 1000) return Math.min(BATCH_SIZE_SMALL, totalSize);
        if (totalSize <= 10_000) return BATCH_SIZE_MEDIUM;
        if (totalSize <= 50_000) return BATCH_SIZE_LARGE;
        return BATCH_SIZE_MAX;
    }

    private void logProgress(int total, int current, int success, int skipped,
                             long startTime, int batchNum, int totalBatches) {
        long elapsed = System.currentTimeMillis() - startTime;
        double progress = (double) current / total * 100;
        double throughput = elapsed > 0 ? (success * 1000.0) / elapsed : 0;
        long eta = elapsed > 0 ? (long) ((elapsed / progress) * (100 - progress)) : 0;

        long heapUsed = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) >> 20;
        long heapMax = Runtime.getRuntime().maxMemory() >> 20;

        // ✅ BEST PRACTICE - Pre-format values
        String progressStr = String.format("%.1f", progress);
        String throughputStr = String.format("%.0f", throughput);

        log.info("📊 Progress: {}/{} ({}%) | Batch {}/{} | Success: {} | Skip: {} | " +
                        "{} rec/sec | Mem: {}/{} MB | ETA: {} sec",
                current, total, progressStr, batchNum, totalBatches, success, skipped,
                throughputStr, heapUsed, heapMax, eta / 1000);
    }

    /**
     * Extract duplicate key from exception
     */
    private String extractDuplicateKey(Exception e) {
        String msg = e.getMessage();
        if (msg != null && msg.contains("duplicate key")) {
            int start = msg.indexOf("dup key:");
            if (start > 0) {
                return msg.substring(start, Math.min(start + 100, msg.length()));
            }
        }
        return "Unknown duplicate";
    }

    // ==================== READ OPERATIONS ====================

    @Override
    public T findById(ID id) {
        T entity = mongoTemplate.findById(id, entityClass);
        if (entity == null) {
            throw new EntityNotFoundException(getEntityClassName(), id);
        }
        return entity;
    }

    @Override
    public List<T> findAll() {
        long totalCount = count();

        if (totalCount > MAX_IN_MEMORY_THRESHOLD) {
            log.warn("Large dataset ({} records) - using streaming", totalCount);
            return findAllBatched(null);
        }

        return mongoTemplate.findAll(entityClass);
    }

    @Override
    public List<T> findAll(Sort sort) {
        long totalCount = count();

        if (totalCount > MAX_IN_MEMORY_THRESHOLD) {
            return findAllBatched(sort);
        }

        Query query = new Query().with(sort);
        return mongoTemplate.find(query, entityClass);
    }

    /**
     * 🔥 Batched retrieval for large datasets
     */
    private List<T> findAllBatched(Sort sort) {
        List<T> result = new ArrayList<>();
        int skip = 0;
        int fetchSize = 50;

        while (true) {
            Query query = new Query().skip(skip).limit(fetchSize);
            if (sort != null) query.with(sort);

            List<T> batch = mongoTemplate.find(query, entityClass);
            if (batch.isEmpty()) break;

            result.addAll(batch);
            skip += fetchSize;

            if (batch.size() < fetchSize) break;
        }

        return result;
    }

    @Override
    public Page<T> findAll(Pageable pageable) {
        Query query = new Query().with(pageable);
        List<T> content = mongoTemplate.find(query, entityClass);
        long total = mongoTemplate.count(new Query(), entityClass);
        return new PageImpl<>(content, pageable, total);
    }

    // ==================== UPDATE OPERATIONS ====================

    @Override
    public T update(ID id, Map<String, Object> updates) {
        T existingEntity = findById(id);
        autoValidateUpdates(updates, existingEntity);

        Query query = new Query(Criteria.where("_id").is(id));
        Update update = new Update();

        updates.forEach((key, value) -> {
            if (!"id".equals(key) && !"_id".equals(key)) {
                update.set(key, value);
            }
        });

        existingEntity.onUpdate();
        update.set("audit.updatedAt", existingEntity.getAudit().getUpdatedAt());

        mongoTemplate.updateFirst(query, update, entityClass);
        return findById(id);
    }

    @Override
    @Transactional(timeout = 600)
    public BatchResult<T> updateBatch(Map<ID, Map<String, Object>> updates) {
        int successCount = 0;
        int skipCount = 0;
        List<String> skipReasons = new ArrayList<>();

        for (Map.Entry<ID, Map<String, Object>> entry : updates.entrySet()) {
            try {
                update(entry.getKey(), entry.getValue());
                successCount++;
            } catch (Exception e) {
                skipCount++;
                if (skipReasons.size() < 1000) {
                    skipReasons.add("ID " + entry.getKey() + ": " + e.getMessage());
                }
            }
        }

        BatchResult<T> result = new BatchResult<>();
        result.setCreatedEntities(Collections.emptyList());
        result.setSkippedCount(skipCount);
        result.setSkippedReasons(skipReasons);
        return result;
    }

    // ==================== DELETE OPERATIONS ====================

    @Override
    public T delete(ID id) {
        Query query = new Query(Criteria.where("_id").is(id));
        T entity = mongoTemplate.findAndRemove(query, entityClass);
        if (entity == null) {
            throw new EntityNotFoundException(getEntityClassName(), id);
        }
        return entity;
    }

    @Override
    public BatchResult<T> deleteBatch(List<ID> ids) {
        Query findQuery = Query.query(Criteria.where("_id").in(ids));
        List<T> found = mongoTemplate.find(findQuery, entityClass);

        Set<ID> foundIds = found.stream().map(T::getId).collect(Collectors.toSet());
        int notFound = ids.size() - foundIds.size();

        List<String> skipReasons = new ArrayList<>();
        for (ID id : ids) {
            if (!foundIds.contains(id)) {
                if (skipReasons.size() < 1000) {
                    skipReasons.add("ID " + id + " not found");
                }
            }
        }

        if (!foundIds.isEmpty()) {
            Query deleteQuery = Query.query(Criteria.where("_id").in(foundIds));
            mongoTemplate.remove(deleteQuery, entityClass);
        }

        BatchResult<T> result = new BatchResult<>();
        result.setCreatedEntities(Collections.emptyList());
        result.setSkippedCount(notFound);
        result.setSkippedReasons(skipReasons);
        return result;
    }

    // ==================== UTILITY ====================

    @Override
    public long count() {
        return mongoTemplate.count(new Query(), entityClass);
    }

    @Override
    public boolean existsById(ID id) {
        return mongoTemplate.exists(Query.query(Criteria.where("_id").is(id)), entityClass);
    }

    /**
     * 🔥 Cached unique constraints
     */
    private void cacheUniqueConstraints() {
        if (UNIQUE_CONSTRAINT_CACHE.containsKey(entityClass)) return;

        List<UniqueConstraintMeta> constraints = new ArrayList<>();
        CrudXUniqueConstraint[] annotations = entityClass.getAnnotationsByType(CrudXUniqueConstraint.class);

        for (CrudXUniqueConstraint ann : annotations) {
            List<Field> fields = new ArrayList<>();
            for (String fieldName : ann.fields()) {
                try {
                    Field field = getFieldFromClass(entityClass, fieldName);
                    field.setAccessible(true);
                    fields.add(field);
                } catch (Exception e) {
                    log.warn("Field not found: {}", fieldName);
                }
            }
            if (!fields.isEmpty()) {
                constraints.add(new UniqueConstraintMeta(fields, ann.message()));
            }
        }

        UNIQUE_CONSTRAINT_CACHE.put(entityClass, constraints);
    }

    /**
     * Validate unique constraints (DB query)
     * Only for single creates, NOT batch
     */
    private void validateUniqueConstraints(T entity) {
        List<UniqueConstraintMeta> constraints = UNIQUE_CONSTRAINT_CACHE.get(entityClass);
        if (constraints == null || constraints.isEmpty()) return;

        for (UniqueConstraintMeta meta : constraints) {
            Query query = new Query();

            for (Field field : meta.fields) {
                try {
                    Object value = field.get(entity);
                    if (value != null) {
                        query.addCriteria(Criteria.where(field.getName()).is(value));
                    }
                } catch (Exception e) {
                    log.debug("Field access error: {}", field.getName());
                }
            }

            if (entity.getId() != null) {
                query.addCriteria(Criteria.where("_id").ne(entity.getId()));
            }

            if (mongoTemplate.exists(query, entityClass)) {
                String msg = meta.message.isEmpty() ?
                        "Duplicate entry for unique constraint" : meta.message;
                throw new DuplicateEntityException(msg);
            }
        }
    }

    private void autoValidateUpdates(Map<String, Object> updates, T entity) {
        List<String> protectedFields = List.of("_id", "id", "createdAt", "created_at", "createdBy", "created_by");

        for (String field : protectedFields) {
            if (updates.containsKey(field)) {
                throw new IllegalArgumentException("Cannot update protected field: " + field);
            }
        }

        for (String fieldName : updates.keySet()) {
            try {
                Field field = getFieldFromClass(entityClass, fieldName);
                if (field.isAnnotationPresent(io.github.sachinnimbal.crudx.core.annotations.CrudXImmutable.class)) {
                    throw new IllegalArgumentException("Field '" + fieldName + "' is immutable");
                }
            } catch (NoSuchFieldException e) {
                throw new IllegalArgumentException("Field '" + fieldName + "' does not exist");
            }
        }

        if (validator != null) {
            Map<String, Object> oldValues = new HashMap<>();
            try {
                for (Map.Entry<String, Object> entry : updates.entrySet()) {
                    Field field = getFieldFromClass(entityClass, entry.getKey());
                    field.setAccessible(true);
                    oldValues.put(entry.getKey(), field.get(entity));
                    field.set(entity, entry.getValue());
                }

                Set<ConstraintViolation<T>> violations = validator.validate(entity);
                if (!violations.isEmpty()) {
                    String errors = violations.stream()
                            .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                            .collect(Collectors.joining(", "));
                    throw new IllegalArgumentException("Validation failed: " + errors);
                }
            } catch (IllegalAccessException | NoSuchFieldException e) {
                throw new RuntimeException(e);
            } finally {
                oldValues.forEach((key, val) -> {
                    try {
                        Field field = getFieldFromClass(entityClass, key);
                        field.setAccessible(true);
                        field.set(entity, val);
                    } catch (Exception e) {
                        log.debug("Rollback error", e);
                    }
                });
            }
        }

        validateUniqueConstraints(entity);
    }

    private Field getFieldFromClass(Class<?> clazz, String fieldName) throws NoSuchFieldException {
        try {
            return clazz.getDeclaredField(fieldName);
        } catch (NoSuchFieldException e) {
            if (clazz.getSuperclass() != null) {
                return getFieldFromClass(clazz.getSuperclass(), fieldName);
            }
            throw e;
        }
    }

    private String getEntityClassName() {
        return entityClass != null ? entityClass.getSimpleName() : "Unknown";
    }

    private static class UniqueConstraintMeta {
        final List<Field> fields;
        final String message;

        UniqueConstraintMeta(List<Field> fields, String message) {
            this.fields = fields;
            this.message = message;
        }
    }
}