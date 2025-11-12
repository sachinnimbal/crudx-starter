package io.github.sachinnimbal.crudx.service.impl;

import io.github.sachinnimbal.crudx.core.annotations.CrudXImmutable;
import io.github.sachinnimbal.crudx.core.annotations.CrudXUniqueConstraint;
import io.github.sachinnimbal.crudx.core.annotations.CrudXUniqueConstraints;
import io.github.sachinnimbal.crudx.core.config.CrudXProperties;
import io.github.sachinnimbal.crudx.core.exception.DuplicateEntityException;
import io.github.sachinnimbal.crudx.core.exception.EntityNotFoundException;
import io.github.sachinnimbal.crudx.core.model.CrudXBaseEntity;
import io.github.sachinnimbal.crudx.core.response.BatchResult;
import io.github.sachinnimbal.crudx.service.CrudXService;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.*;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.GenericTypeResolver;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Transactional
public abstract class CrudXSQLService<T extends CrudXBaseEntity<ID>, ID extends Serializable>
        implements CrudXService<T, ID> {

    @Autowired(required = false)
    protected EntityManager entityManager;

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
        if (entityManager == null) {
            throw new IllegalStateException(
                    "EntityManager not available. Add 'spring-boot-starter-data-jpa' and DB driver.");
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

        Class<?>[] typeArgs = GenericTypeResolver.resolveTypeArguments(getClass(), CrudXSQLService.class);
        if (typeArgs != null && typeArgs.length > 0) {
            entityClass = (Class<T>) typeArgs[0];
            return;
        }

        throw new IllegalStateException("Could not resolve entity class: " + getClass().getSimpleName());
    }

    @Override
    @Transactional(timeout = 300, isolation = Isolation.READ_COMMITTED)
    public T create(T entity) {
        long start = System.currentTimeMillis();
        log.debug("Creating entity: {}", getEntityClassName());

        validateUniqueConstraints(entity);
        entityManager.persist(entity);
        entityManager.flush();

        log.info("Entity created: {} in {} ms", entity.getId(), System.currentTimeMillis() - start);
        return entity;
    }

    @Override
    @Transactional(timeout = 1800, isolation = Isolation.READ_COMMITTED, propagation = Propagation.REQUIRED)
    public BatchResult<T> createBatch(List<T> entities, boolean skipDuplicates) {
        long startTime = System.currentTimeMillis();
        int totalSize = entities.size();

        log.info("🚀 SQL Batch: {} entities | Mode: {} | Isolation: READ_COMMITTED",
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

        // Build in-memory duplicate detection for current batch
        Set<String> inMemoryConstraintKeys = new HashSet<>();

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

                    // Check in-memory duplicates FIRST (within same batch)
                    String constraintKey = buildConstraintKey(entity);
                    if (constraintKey != null && !constraintKey.isEmpty() && inMemoryConstraintKeys.contains(constraintKey)) {
                        skipCount++;
                        duplicateSkipCount++;
                        if (skipReasons.size() < 1000) {
                            skipReasons.add(String.format("Index %d: Duplicate within batch - %s",
                                    i, buildDuplicateMessage(entity)));
                        }

                        if (!skipDuplicates) {
                            log.error("Batch {} aborted: In-memory duplicate at index {}", batchNumber, i);
                            throw new DuplicateEntityException("Duplicate within batch at index " + i + ": " +
                                    buildDuplicateMessage(entity));
                        }

                        log.debug("Skipped in-memory duplicate at index {}", i);
                        entities.set(i, null);
                        continue;
                    }

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

                    // Add to in-memory tracking
                    if (constraintKey != null && !constraintKey.isEmpty()) {
                        inMemoryConstraintKeys.add(constraintKey);
                    }

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
                    for (T entity : chunkEntities) {
                        entityManager.persist(entity);
                        successCount++;

                        // Periodic flush to maintain memory
                        if (successCount % 100 == 0) {
                            entityManager.flush();
                        }
                    }

                    entityManager.flush();
                    entityManager.clear();

                } catch (PersistenceException e) {
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

                    entityManager.clear();
                    log.warn("Batch {} had DB constraint violations, skipped", batchNumber);

                } catch (Exception e) {
                    log.error("Batch {} insert failed: {}", batchNumber, e.getMessage());
                    if (!skipDuplicates) {
                        throw new RuntimeException("Insert failed in batch " + batchNumber, e);
                    }

                    skipCount += chunkEntities.size();
                    entityManager.clear();
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
        inMemoryConstraintKeys.clear();

        long duration = System.currentTimeMillis() - startTime;
        double throughput = duration > 0 ? (successCount * 1000.0) / duration : 0.0;

        log.info("✅ SQL Batch Complete: {} success, {} skipped (duplicates: {}, validation: {}) | {} rec/sec | {} ms",
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
     * Build unique constraint key for in-memory deduplication within batch
     */
    private String buildConstraintKey(T entity) {
        CrudXUniqueConstraint[] constraints = getUniqueConstraints();
        if (constraints == null || constraints.length == 0) {
            return null;
        }

        StringBuilder key = new StringBuilder();
        boolean hasAnyValue = false;

        for (CrudXUniqueConstraint constraint : constraints) {
            key.append(constraint.name()).append(":");
            boolean allFieldsHaveValues = true;

            for (String fieldName : constraint.fields()) {
                try {
                    Field field = getFieldFromClass(entityClass, fieldName);
                    field.setAccessible(true);
                    Object value = field.get(entity);

                    if (value == null) {
                        allFieldsHaveValues = false;
                        break;
                    }
                    key.append(fieldName).append("=").append(value).append("|");
                    hasAnyValue = true;
                } catch (Exception e) {
                    log.debug("Cannot access field: {}", fieldName);
                    allFieldsHaveValues = false;
                    break;
                }
            }

            // Only include this constraint if all its fields have values
            if (!allFieldsHaveValues) {
                int lastColon = key.lastIndexOf(constraint.name() + ":");
                if (lastColon >= 0) {
                    key.setLength(lastColon);
                }
            } else {
                key.append(";");
            }
        }

        return hasAnyValue ? key.toString() : null;
    }

    /**
     * Validate unique constraints using direct DB query
     */
    private void validateUniqueConstraints(T entity) {
        if (violatesUniqueConstraints(entity)) {
            String duplicateMsg = buildDuplicateMessage(entity);
            log.error("Duplicate entity: {}", duplicateMsg);
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
     * Query database to check for duplicates (no lock)
     */
    private boolean checkDuplicateInDB(T entity, CrudXUniqueConstraint constraint) {
        try {
            CriteriaBuilder cb = entityManager.getCriteriaBuilder();
            CriteriaQuery<Long> query = cb.createQuery(Long.class);
            Root<T> root = query.from(entityClass);

            List<Predicate> predicates = new ArrayList<>();
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
                    predicates.add(cb.equal(root.get(fieldName), value));
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
                predicates.add(cb.notEqual(root.get("id"), entity.getId()));
            }

            if (predicates.isEmpty()) {
                return false;
            }

            query.select(cb.count(root)).where(predicates.toArray(new Predicate[0]));

            TypedQuery<Long> typedQuery = entityManager.createQuery(query);
            Long count = typedQuery.getSingleResult();

            return count > 0;

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
    @Transactional(readOnly = true)
    public T findById(ID id) {
        T entity = entityManager.find(entityClass, id);
        if (entity == null) {
            throw new EntityNotFoundException(getEntityClassName(), id);
        }
        return entity;
    }

    @Override
    @Transactional(readOnly = true)
    public List<T> findAll() {
        long totalCount = count();

        if (totalCount > MAX_IN_MEMORY_THRESHOLD) {
            log.warn("Large dataset ({} records) - using streaming", totalCount);
            return findAllStreaming(null);
        }

        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<T> query = cb.createQuery(entityClass);
        query.select(query.from(entityClass));

        return entityManager.createQuery(query).getResultList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<T> findAll(Sort sort) {
        long totalCount = count();

        if (totalCount > MAX_IN_MEMORY_THRESHOLD) {
            return findAllStreaming(sort);
        }

        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<T> query = cb.createQuery(entityClass);
        Root<T> root = query.from(entityClass);
        query.select(root);

        List<Order> orders = new ArrayList<>();
        sort.forEach(order -> {
            orders.add(order.isAscending() ? cb.asc(root.get(order.getProperty()))
                    : cb.desc(root.get(order.getProperty())));
        });
        query.orderBy(orders);

        return entityManager.createQuery(query).getResultList();
    }

    @Transactional(readOnly = true)
    private List<T> findAllStreaming(Sort sort) {
        List<T> result = new ArrayList<>();
        int offset = 0;
        int fetchSize = 50;

        while (true) {
            CriteriaBuilder cb = entityManager.getCriteriaBuilder();
            CriteriaQuery<T> query = cb.createQuery(entityClass);
            Root<T> root = query.from(entityClass);
            query.select(root);

            if (sort != null) {
                List<Order> orders = new ArrayList<>();
                sort.forEach(order -> {
                    orders.add(order.isAscending() ? cb.asc(root.get(order.getProperty()))
                            : cb.desc(root.get(order.getProperty())));
                });
                query.orderBy(orders);
            }

            TypedQuery<T> typedQuery = entityManager.createQuery(query);
            typedQuery.setFirstResult(offset);
            typedQuery.setMaxResults(fetchSize);

            List<T> batch = typedQuery.getResultList();
            if (batch.isEmpty()) break;

            result.addAll(batch);
            offset += fetchSize;
            entityManager.clear();

            if (batch.size() < fetchSize) break;
        }

        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<T> findAll(Pageable pageable) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<T> query = cb.createQuery(entityClass);
        Root<T> root = query.from(entityClass);
        query.select(root);

        if (pageable.getSort().isSorted()) {
            List<Order> orders = new ArrayList<>();
            pageable.getSort().forEach(order -> {
                orders.add(order.isAscending() ? cb.asc(root.get(order.getProperty()))
                        : cb.desc(root.get(order.getProperty())));
            });
            query.orderBy(orders);
        }

        List<T> content = entityManager.createQuery(query)
                .setFirstResult((int) pageable.getOffset())
                .setMaxResults(pageable.getPageSize())
                .getResultList();

        long total = count();
        return new PageImpl<>(content, pageable, total);
    }

    // ==================== UPDATE OPERATIONS ====================

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public T update(ID id, Map<String, Object> updates) {
        T entity = findById(id);
        autoValidateUpdates(updates, entity);

        updates.forEach((key, value) -> {
            if (!"id".equals(key)) {
                try {
                    Field field = getFieldFromClass(entityClass, key);
                    field.setAccessible(true);
                    field.set(entity, value);
                } catch (Exception e) {
                    log.warn("Could not update field: {}", key, e);
                }
            }
        });

        try {
            java.lang.reflect.Method onUpdate = entityClass.getMethod("onUpdate");
            onUpdate.invoke(entity);
        } catch (Exception e) {
            // Ignore if onUpdate not present
        }

        // Validate unique constraints using DB query
        validateUniqueConstraints(entity);

        entityManager.merge(entity);
        entityManager.flush();
        return entity;
    }

    @Override
    @Transactional(timeout = 600, isolation = Isolation.READ_COMMITTED)
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
                    entityManager.flush();
                    entityManager.clear();
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
        T entity = findById(id);
        entityManager.remove(entity);
        entityManager.flush();
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

            for (ID id : batch) {
                try {
                    T entity = entityManager.find(entityClass, id);
                    if (entity != null) {
                        entityManager.remove(entity);
                        deleted++;
                    } else {
                        notFound++;
                        if (skipReasons.size() < 1000) {
                            skipReasons.add("ID " + id + " not found");
                        }
                    }
                } catch (Exception e) {
                    notFound++;
                    if (skipReasons.size() < 1000) {
                        skipReasons.add("ID " + id + ": " + e.getMessage());
                    }
                }
            }

            entityManager.flush();
            entityManager.clear();
        }

        BatchResult<T> result = new BatchResult<>();
        result.setCreatedEntities(Collections.emptyList());
        result.setSkippedCount(notFound);
        result.setSkippedReasons(skipReasons);
        return result;
    }

    // ==================== UTILITY METHODS ====================

    @Override
    @Transactional(readOnly = true)
    public long count() {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> query = cb.createQuery(Long.class);
        query.select(cb.count(query.from(entityClass)));
        return entityManager.createQuery(query).getSingleResult();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsById(ID id) {
        return entityManager.find(entityClass, id) != null;
    }

    /**
     * Auto-validate updates using annotations and DB checks
     */
    private void autoValidateUpdates(Map<String, Object> updates, T entity) {
        // 1. Protect system fields
        List<String> protectedFields = List.of("id", "createdAt", "created_at", "createdBy", "created_by");

        for (String field : protectedFields) {
            if (updates.containsKey(field)) {
                throw new IllegalArgumentException("Cannot update protected field: " + field);
            }
        }

        // 2. Check immutable fields and field existence
        for (String fieldName : updates.keySet()) {
            try {
                Field field = getFieldFromClass(entityClass, fieldName);
                if (field.isAnnotationPresent(CrudXImmutable.class)) {
                    CrudXImmutable ann = field.getAnnotation(CrudXImmutable.class);
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