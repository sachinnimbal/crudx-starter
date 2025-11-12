package io.github.sachinnimbal.crudx.service.impl;

import io.github.sachinnimbal.crudx.core.annotations.CrudXUniqueConstraint;
import io.github.sachinnimbal.crudx.core.annotations.CrudXUniqueConstraints;
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
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 🚀 MongoDB Service with DB-based Unique Constraint Validation (matches SQL implementation)
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
    private static final int BATCH_SIZE_SMALL = 200;
    private static final int BATCH_SIZE_MEDIUM = 500;
    private static final int BATCH_SIZE_LARGE = 1000;
    private static final int BATCH_SIZE_X_LARGE = 2000;
    private static final int BATCH_SIZE_MAX = 5000;

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
                log.debug("Entity class: {}", entityClass.getSimpleName());
                return;
            }
        }

        Class<?>[] typeArgs = GenericTypeResolver.resolveTypeArguments(getClass(), CrudXMongoService.class);
        if (typeArgs != null && typeArgs.length > 0) {
            entityClass = (Class<T>) typeArgs[0];
            return;
        }

        throw new IllegalStateException("Could not resolve entity class: " + getClass().getSimpleName());
    }

    @Override
    @Transactional(timeout = 300)
    public T create(T entity) {
        long start = System.currentTimeMillis();
        log.debug("Creating entity: {}", getEntityClassName());

        validateUniqueConstraints(entity);
        entity.onCreate();
        T saved = mongoTemplate.save(entity);

        log.info("Entity created: {} in {} ms", saved.getId(), System.currentTimeMillis() - start);
        return saved;
    }

    @Override
    @Transactional(timeout = 1800)
    public BatchResult<T> createBatch(List<T> entities, boolean skipDuplicates) {
        long startTime = System.currentTimeMillis();
        int totalSize = entities.size();

        log.info("🚀 MongoDB Batch: {} entities | Mode: {} | Isolation: READ_COMMITTED",
                totalSize, skipDuplicates ? "SKIP_DUPLICATES" : "ABORT_ON_ERROR");

        int batchSize = calculateOptimalBatchSize(totalSize);

        int successCount = 0;
        int skipCount = 0;
        int duplicateSkipCount = 0;
        int validationSkipCount = 0;
        int batchNumber = 0;
        int totalBatches = (totalSize + batchSize - 1) / batchSize;

        List<String> skipReasons = new ArrayList<>(Math.min(1000, totalSize / 10));

        long maxProcessingTime = 300000; // 5 minutes
        long processingDeadline = startTime + maxProcessingTime;

        int dbBatchSize = calculateOptimalBatchSize(totalSize);

        for (int chunkStart = 0; chunkStart < totalSize; chunkStart += dbBatchSize) {
            batchNumber++;

            // Timeout check
            if (System.currentTimeMillis() > processingDeadline) {
                return buildTimeoutResult(totalSize, successCount, skipCount,
                        duplicateSkipCount, validationSkipCount, startTime, skipReasons);
            }

            int chunkEnd = Math.min(chunkStart + dbBatchSize, totalSize);
            List<T> chunkEntities = new ArrayList<>(chunkEnd - chunkStart);

            // Conversion & Validation Phase
            for (int i = chunkStart; i < chunkEnd; i++) {
                T entity = entities.get(i);
                if (entity == null) continue;

                try {
                    // Jakarta Bean Validation
                    validateJakartaValidation(entity);

                    // Check DB duplicates using direct query
                    if (violatesUniqueConstraints(entity)) {
                        skipCount++;
                        duplicateSkipCount++;

                        if (skipReasons.size() < 1000) {
                            String duplicateMsg = buildDuplicateMessage(entity);
                            skipReasons.add(String.format("Index %d: %s", i, duplicateMsg));
                        }

                        if (!skipDuplicates) {
                            log.error("Batch {} aborted: DB duplicate at index {}", batchNumber, i);
                            throw new DuplicateEntityException("Duplicate at index " + i + ": " +
                                    buildDuplicateMessage(entity));
                        }

                        log.debug("Skipped DB duplicate at index {}", i);
                        entities.set(i, null);
                        continue;
                    }

                    entity.onCreate();
                    chunkEntities.add(entity);

                } catch (DuplicateEntityException e) {
                    throw e; // Re-throw if abort mode
                } catch (Exception e) {
                    skipCount++;
                    validationSkipCount++;
                    if (skipReasons.size() < 1000) {
                        skipReasons.add(String.format("Index %d: Validation failed - %s", i, e.getMessage()));
                    }
                    log.debug("Validation failed at index {}: {}", i, e.getMessage());

                    if (!skipDuplicates) {
                        throw new IllegalArgumentException("Validation failed at index " + i, e);
                    }
                }

                entities.set(i, null); // Free memory
            }

            // Database Insert Phase
            if (!chunkEntities.isEmpty()) {
                try {
                    BulkOperations bulkOps = mongoTemplate.bulkOps(
                            skipDuplicates ? BulkOperations.BulkMode.UNORDERED : BulkOperations.BulkMode.ORDERED,
                            entityClass);

                    bulkOps.insert(chunkEntities);
                    com.mongodb.bulk.BulkWriteResult result = bulkOps.execute();

                    int inserted = result.getInsertedCount();
                    successCount += inserted;

                    // MongoDB might still skip some due to race conditions
                    int dbSkipped = chunkEntities.size() - inserted;
                    if (dbSkipped > 0) {
                        skipCount += dbSkipped;
                        duplicateSkipCount += dbSkipped;

                        if (skipReasons.size() < 1000) {
                            skipReasons.add(String.format("Batch %d: %d MongoDB duplicate key errors",
                                    batchNumber, dbSkipped));
                        }
                    }

                } catch (org.springframework.dao.DuplicateKeyException e) {
                    // DB constraint violation
                    skipCount++;
                    duplicateSkipCount++;

                    if (skipReasons.size() < 1000) {
                        skipReasons.add(String.format("Batch %d: DB constraint violation - %s",
                                batchNumber, extractRootCause(e)));
                    }

                    if (!skipDuplicates) {
                        throw new DuplicateEntityException("Database constraint violation in batch " +
                                batchNumber + ": " + extractRootCause(e));
                    }

                    log.warn("Batch {} had DB constraint violations, skipped", batchNumber);

                } catch (Exception e) {
                    log.error("Batch {} insert failed: {}", batchNumber, e.getMessage());
                    if (!skipDuplicates) {
                        throw new RuntimeException("Insert failed in batch " + batchNumber, e);
                    }

                    skipCount += chunkEntities.size();
                }
            }

            chunkEntities.clear();

            // Progress logging
            if (batchNumber % 10 == 0 || batchNumber == totalBatches) {
                logBatchProgress(totalSize, chunkEnd, successCount, skipCount,
                        duplicateSkipCount, validationSkipCount, startTime, batchNumber, totalBatches);
            }

            // Memory management
            if (batchNumber % 50 == 0) {
                System.gc();
            }
        }

        entities.clear();

        long duration = System.currentTimeMillis() - startTime;
        double throughput = duration > 0 ? (successCount * 1000.0) / duration : 0.0;

        log.info("✅ MongoDB Batch Complete: {} success, {} skipped (duplicates: {}, validation: {}) | {} rec/sec | {} ms",
                successCount, skipCount, duplicateSkipCount, validationSkipCount,
                String.format("%.0f", throughput), duration);

        BatchResult<T> result = new BatchResult<>();
        result.setCreatedEntities(Collections.emptyList());
        result.setSuccessCount(successCount);
        result.setSkippedCount(skipCount);
        result.setDuplicateSkipCount(duplicateSkipCount);
        result.setSkippedReasons(skipReasons.isEmpty() ? null : skipReasons);

        return result;
    }

    /**
     * Validate unique constraints using direct DB query
     */
    private void validateUniqueConstraints(T entity) {
        if (violatesUniqueConstraints(entity)) {
            String duplicateMsg = buildDuplicateMessage(entity);
            throw new DuplicateEntityException(duplicateMsg);
        }
    }

    /**
     * Check if entity violates unique constraints in DB
     */
    private boolean violatesUniqueConstraints(T entity) {
        CrudXUniqueConstraint[] constraints = getUniqueConstraints();
        if (constraints == null || constraints.length == 0) {
            return false;
        }

        for (CrudXUniqueConstraint constraint : constraints) {
            if (checkDuplicateInDB(entity, constraint)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Query MongoDB to check for duplicates (no lock)
     */
    private boolean checkDuplicateInDB(T entity, CrudXUniqueConstraint constraint) {
        try {
            Query query = new Query();
            boolean hasAllValues = true;

            // All fields in constraint must have values
            for (String fieldName : constraint.fields()) {
                try {
                    Field field = getFieldFromClass(entityClass, fieldName);
                    field.setAccessible(true);
                    Object value = field.get(entity);

                    if (value == null) {
                        hasAllValues = false;
                        break;
                    }
                    query.addCriteria(Criteria.where(fieldName).is(value));
                } catch (Exception e) {
                    log.debug("Field access error: {}", fieldName);
                    return false;
                }
            }

            // If any field is null in compound constraint, skip validation
            if (!hasAllValues) {
                return false;
            }

            // Exclude current entity if updating
            if (entity.getId() != null) {
                query.addCriteria(Criteria.where("_id").ne(entity.getId()));
            }

            return mongoTemplate.exists(query, entityClass);

        } catch (Exception e) {
            log.warn("Duplicate check failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Build human-readable duplicate message
     */
    private String buildDuplicateMessage(T entity) {
        CrudXUniqueConstraint[] constraints = getUniqueConstraints();
        if (constraints == null || constraints.length == 0) {
            return "Duplicate entry detected";
        }

        for (CrudXUniqueConstraint constraint : constraints) {
            if (checkDuplicateInDB(entity, constraint)) {
                if (!constraint.message().isEmpty()) {
                    return constraint.message();
                }

                StringBuilder msg = new StringBuilder("Duplicate constraint '");
                msg.append(constraint.name()).append("': Fields [");

                for (String fieldName : constraint.fields()) {
                    try {
                        Field field = getFieldFromClass(entityClass, fieldName);
                        field.setAccessible(true);
                        Object value = field.get(entity);
                        msg.append(fieldName).append("=").append(value).append(", ");
                    } catch (Exception e) {
                        msg.append(fieldName).append(", ");
                    }
                }
                msg.setLength(msg.length() - 2);
                msg.append("] already exist");
                return msg.toString();
            }
        }

        return "Duplicate entry detected";
    }

    /**
     * Get unique constraints from entity class
     */
    private CrudXUniqueConstraint[] getUniqueConstraints() {
        CrudXUniqueConstraints containerAnnotation = entityClass.getAnnotation(CrudXUniqueConstraints.class);

        if (containerAnnotation != null) {
            return containerAnnotation.value();
        }

        return entityClass.getAnnotationsByType(CrudXUniqueConstraint.class);
    }

    /**
     * Jakarta Bean Validation
     */
    private void validateJakartaValidation(T entity) {
        if (validator != null) {
            Set<ConstraintViolation<T>> violations = validator.validate(entity);
            if (!violations.isEmpty()) {
                String errors = violations.stream()
                        .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                        .collect(Collectors.joining(", "));
                throw new IllegalArgumentException("Validation failed: " + errors);
            }
        }
    }

    private int calculateOptimalBatchSize(int totalSize) {
        if (totalSize <= 1000) return BATCH_SIZE_SMALL;
        if (totalSize <= 10_000) return BATCH_SIZE_MEDIUM;
        if (totalSize <= 50_000) return BATCH_SIZE_LARGE;
        if (totalSize <= 100_000) return BATCH_SIZE_X_LARGE;
        return BATCH_SIZE_MAX;
    }

    private void logBatchProgress(int total, int current, int success, int skipped,
                                  int duplicates, int validationFails, long startTime,
                                  int batchNum, int totalBatches) {
        long elapsed = System.currentTimeMillis() - startTime;
        double progress = (double) current / total * 100;
        double throughput = elapsed > 0 ? (success * 1000.0) / elapsed : 0;
        long eta = elapsed > 0 ? (long) ((elapsed / progress) * (100 - progress)) : 0;

        long heapUsed = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) >> 20;
        long heapMax = Runtime.getRuntime().maxMemory() >> 20;

        String message = String.format(
                "📊 Progress: %d/%d (%.1f%%) | Batch %d/%d | Success: %d | " +
                        "Skipped: %d (Duplicates: %d, Validation: %d) | %.0f rec/sec | " +
                        "Mem: %d/%d MB | ETA: %d sec",
                current, total, progress, batchNum, totalBatches, success,
                skipped, duplicates, validationFails, throughput,
                heapUsed, heapMax, eta / 1000
        );

        log.info(message);
    }

    private String extractRootCause(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String msg = cause.getMessage();
        return msg != null && msg.length() > 100 ? msg.substring(0, 100) + "..." : msg;
    }

    private BatchResult<T> buildTimeoutResult(int totalSize, int successCount, int skipCount,
                                              int duplicateCount, int validationCount,
                                              long startTime, List<String> skipReasons) {
        long duration = System.currentTimeMillis() - startTime;

        log.error("Batch processing timeout after {} ms. Processed {}/{} records (duplicates: {}, validation: {})",
                duration, successCount, totalSize, duplicateCount, validationCount);

        BatchResult<T> result = new BatchResult<>();
        result.setCreatedEntities(Collections.emptyList());
        result.setSuccessCount(successCount);
        result.setSkippedCount(skipCount);
        result.setDuplicateSkipCount(duplicateCount);
        result.setSkippedReasons(skipReasons);

        return result;
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
            return findAllStreaming(null);
        }

        return mongoTemplate.findAll(entityClass);
    }

    @Override
    public List<T> findAll(Sort sort) {
        long totalCount = count();

        if (totalCount > MAX_IN_MEMORY_THRESHOLD) {
            return findAllStreaming(sort);
        }

        Query query = new Query().with(sort);
        return mongoTemplate.find(query, entityClass);
    }

    private List<T> findAllStreaming(Sort sort) {
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
    @Transactional(timeout = 300, isolation = Isolation.READ_COMMITTED)
    public T update(ID id, Map<String, Object> updates) {
        T entity = findById(id);
        autoValidateUpdates(updates, entity);

        updates.forEach((key, value) -> {
            if (!"id".equals(key) && !"_id".equals(key)) {
                try {
                    Field field = getFieldFromClass(entityClass, key);
                    field.setAccessible(true);
                    field.set(entity, value);
                } catch (Exception e) {
                    log.warn("Could not update field: {}", key, e);
                }
            }
        });

        entity.onUpdate();

        // Validate unique constraints using DB query
        validateUniqueConstraints(entity);

        Query query = new Query(Criteria.where("_id").is(id));
        Update update = new Update();

        updates.forEach((key, value) -> {
            if (!"id".equals(key) && !"_id".equals(key)) {
                update.set(key, value);
            }
        });
        update.set("audit.updatedAt", entity.getAudit().getUpdatedAt());

        mongoTemplate.updateFirst(query, update, entityClass);
        return findById(id);
    }

    @Override
    @Transactional(timeout = 600)
    public BatchResult<T> updateBatch(Map<ID, Map<String, Object>> updates) {
        int successCount = 0;
        int skipCount = 0;
        int duplicateSkipCount = 0;
        List<String> skipReasons = new ArrayList<>();

        int processed = 0;
        for (Map.Entry<ID, Map<String, Object>> entry : updates.entrySet()) {
            try {
                update(entry.getKey(), entry.getValue());
                successCount++;
                processed++;

                if (processed % 50 == 0) {
                    // MongoDB doesn't need flush/clear like JPA
                    log.debug("Processed {} updates", processed);
                }

            } catch (DuplicateEntityException e) {
                skipCount++;
                duplicateSkipCount++;
                if (skipReasons.size() < 1000) {
                    skipReasons.add("ID " + entry.getKey() + ": " + e.getMessage());
                }
            } catch (Exception e) {
                skipCount++;
                if (skipReasons.size() < 1000) {
                    skipReasons.add("ID " + entry.getKey() + ": " + e.getMessage());
                }
            }
        }

        log.info("Batch update complete: {} success, {} skipped ({} duplicates)",
                successCount, skipCount, duplicateSkipCount);

        BatchResult<T> result = new BatchResult<>();
        result.setCreatedEntities(Collections.emptyList());
        result.setSkippedCount(skipCount);
        result.setDuplicateSkipCount(duplicateSkipCount);
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
        int batchSize = 50;
        int deleted = 0;
        int notFound = 0;
        List<String> skipReasons = new ArrayList<>();

        for (int i = 0; i < ids.size(); i += batchSize) {
            int end = Math.min(i + batchSize, ids.size());
            List<ID> batch = ids.subList(i, end);

            Query findQuery = Query.query(Criteria.where("_id").in(batch));
            List<T> found = mongoTemplate.find(findQuery, entityClass);
            Set<ID> foundIds = found.stream().map(T::getId).collect(Collectors.toSet());

            for (ID id : batch) {
                if (foundIds.contains(id)) {
                    deleted++;
                } else {
                    notFound++;
                    if (skipReasons.size() < 1000) {
                        skipReasons.add("ID " + id + " not found");
                    }
                }
            }

            if (!foundIds.isEmpty()) {
                Query deleteQuery = Query.query(Criteria.where("_id").in(foundIds));
                mongoTemplate.remove(deleteQuery, entityClass);
            }
        }

        BatchResult<T> result = new BatchResult<>();
        result.setCreatedEntities(Collections.emptyList());
        result.setSkippedCount(notFound);
        result.setSkippedReasons(skipReasons);
        return result;
    }

    // ==================== UTILITY METHODS ====================

    @Override
    public long count() {
        return mongoTemplate.count(new Query(), entityClass);
    }

    @Override
    public boolean existsById(ID id) {
        return mongoTemplate.exists(Query.query(Criteria.where("_id").is(id)), entityClass);
    }

    /**
     * Auto-validate updates using annotations and DB checks
     */
    private void autoValidateUpdates(Map<String, Object> updates, T entity) {
        // 1. Protect system fields
        List<String> protectedFields = List.of("id", "_id", "createdAt", "created_at", "createdBy", "created_by");

        for (String field : protectedFields) {
            if (updates.containsKey(field)) {
                throw new IllegalArgumentException("Cannot update protected field: " + field);
            }
        }

        // 2. Check immutable fields and field existence
        for (String fieldName : updates.keySet()) {
            try {
                Field field = getFieldFromClass(entityClass, fieldName);
                if (field.isAnnotationPresent(io.github.sachinnimbal.crudx.core.annotations.CrudXImmutable.class)) {
                    io.github.sachinnimbal.crudx.core.annotations.CrudXImmutable ann =
                            field.getAnnotation(io.github.sachinnimbal.crudx.core.annotations.CrudXImmutable.class);
                    throw new IllegalArgumentException(
                            String.format("Field '%s' is immutable: %s", fieldName, ann.message()));
                }
            } catch (NoSuchFieldException e) {
                throw new IllegalArgumentException("Field '" + fieldName + "' does not exist");
            }
        }

        // 3. Apply updates temporarily for validation
        Map<String, Object> oldValues = new HashMap<>();
        try {
            for (Map.Entry<String, Object> entry : updates.entrySet()) {
                Field field = getFieldFromClass(entityClass, entry.getKey());
                field.setAccessible(true);
                oldValues.put(entry.getKey(), field.get(entity));
                field.set(entity, entry.getValue());
            }

            // 4. Jakarta Bean Validation
            if (validator != null) {
                Set<ConstraintViolation<T>> violations = validator.validate(entity);
                if (!violations.isEmpty()) {
                    String errors = violations.stream()
                            .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                            .collect(Collectors.joining(", "));
                    throw new IllegalArgumentException("Validation failed: " + errors);
                }
            }

            // 5. Check unique constraints using DB query
            validateUniqueConstraints(entity);

        } catch (IllegalAccessException | NoSuchFieldException e) {
            throw new RuntimeException("Validation error", e);
        } finally {
            // Always rollback temporary changes
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
}