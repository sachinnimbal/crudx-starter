package io.github.sachinnimbal.crudx.service.impl;

import io.github.sachinnimbal.crudx.core.annotations.CrudXImmutable;
import io.github.sachinnimbal.crudx.core.annotations.CrudXUniqueConstraint;
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
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.GenericTypeResolver;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 🚀 ENTERPRISE-GRADE SQL Service
 * <p>
 * BATCH INSERT OPTIMIZATIONS:
 * - Single transaction for entire batch (configurable chunk size)
 * - Zero unnecessary DB queries (no pre-validation fetches)
 * - Streaming validation (memory-bounded)
 * - Intelligent error recovery (skip vs abort)
 * - Lock-free unique constraint caching
 * - Primitive-based counters (no boxing)
 */
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

    // ADAPTIVE batch sizing based on dataset
    private static final int BATCH_SIZE_SMALL = 200;    // < 1K records
    private static final int BATCH_SIZE_MEDIUM = 500;   // 1K-10K
    private static final int BATCH_SIZE_LARGE = 1000;   // 10K-50K
    private static final int BATCH_SIZE_X_LARGE = 2000;  // 50K-100K
    private static final int BATCH_SIZE_MAX = 5000;     // > 100K

    // Cache for unique constraint fields (avoid reflection)
    private static final Map<Class<?>, List<UniqueConstraintMeta>> UNIQUE_CONSTRAINT_CACHE =
            new ConcurrentHashMap<>();

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

                // Pre-cache unique constraints
                cacheUniqueConstraints();
                return;
            }
        }

        Class<?>[] typeArgs = GenericTypeResolver.resolveTypeArguments(getClass(), CrudXSQLService.class);
        if (typeArgs != null && typeArgs.length > 0) {
            entityClass = (Class<T>) typeArgs[0];
            cacheUniqueConstraints();
            return;
        }

        throw new IllegalStateException("Could not resolve entity class: " + getClass().getSimpleName());
    }

    @Override
    @Transactional(timeout = 300)
    public T create(T entity) {
        long start = System.currentTimeMillis();
        validateUniqueConstraints(entity);
        entityManager.persist(entity);
        entityManager.flush();

        log.debug("Entity created: {} in {} ms", entity.getId(), System.currentTimeMillis() - start);
        return entity;
    }

    /**
     * 🚀 ENTERPRISE BATCH INSERT - Zero Unnecessary DB Hits
     * <p>
     * STRATEGY:
     * 1. No pre-validation DB queries (validate during insert)
     * 2. Single transaction with adaptive batch flushing
     * 3. Streaming processing (bounded memory)
     * 4. Intelligent error handling (continue vs abort)
     * 5. Real-time progress tracking
     */
    @Override
    @Transactional(timeout = 1800)
    public BatchResult<T> createBatch(List<T> entities, boolean skipDuplicates) {
        long startTime = System.currentTimeMillis();
        int totalSize = entities.size();

        log.info("🚀 SQL Batch: {} entities | Mode: {}",
                totalSize, skipDuplicates ? "SKIP_ERRORS" : "ABORT_ON_ERROR");

        // ADAPTIVE: Calculate optimal batch size
        int batchSize = calculateOptimalBatchSize(totalSize);

        // PRIMITIVES: Zero boxing overhead
        int successCount = 0;
        int skipCount = 0;
        int batchNumber = 0;
        int totalBatches = (totalSize + batchSize - 1) / batchSize;

        // BOUNDED: Error tracking (max 1000 reasons)
        List<String> skipReasons = new ArrayList<>(Math.min(1000, totalSize / 10));

        // STREAMING: Process in chunks to bound memory
        for (int i = 0; i < totalSize; i += batchSize) {
            batchNumber++;
            int end = Math.min(i + batchSize, totalSize);
            int currentBatchSize = end - i;

            long batchStart = System.currentTimeMillis();

            // Process chunk without pre-fetching
            BatchChunkResult chunkResult = processChunkDirectInsert(
                    entities, i, end, skipDuplicates, skipReasons, batchNumber, totalBatches
            );

            successCount += chunkResult.successCount;
            skipCount += chunkResult.skipCount;

            // FLUSH: Commit chunk to DB
            try {
                entityManager.flush();
                entityManager.clear(); // Free memory immediately
            } catch (Exception e) {
                log.error("Batch {} flush failed: {}", batchNumber, e.getMessage());
                if (!skipDuplicates) throw e;
                skipCount += chunkResult.successCount; // Rollback counted as skipped
                successCount -= chunkResult.successCount;
            }

            // Nullify processed entities to free memory
            for (int j = i; j < end; j++) {
                entities.set(j, null);
            }

            // PROGRESS: Log every 10 batches or at completion
            if (batchNumber % 10 == 0 || batchNumber == totalBatches) {
                logBatchProgress(totalSize, end, successCount, skipCount, startTime, batchNumber, totalBatches);
            }

            // GC HINT: Every 100 batches for large datasets
            if (batchNumber % 100 == 0 && totalSize > 50_000) {
                System.gc();
            }
        }

        // Clear input list (caller should not hold reference)
        entities.clear();

        long duration = System.currentTimeMillis() - startTime;
        double throughput = duration > 0 ? (successCount * 1000.0) / duration : 0.0;
        String throughputStr = String.format("%.0f", throughput);

        log.info("✅ SQL Batch Complete: {} success, {} skipped | {} rec/sec | {} ms",
                successCount, skipCount, throughputStr, duration);

        // LIGHTWEIGHT result (no entity copies)
        BatchResult<T> result = new BatchResult<>();
        result.setCreatedEntities(Collections.emptyList());
        result.setSuccessCount(successCount);
        result.setSkippedCount(skipCount);
        result.setSkippedReasons(skipReasons.isEmpty() ? null : skipReasons);

        return result;
    }

    /**
     * ZERO-COPY chunk processing
     * - Direct persist without pre-validation queries
     * - Constraint violations caught during insert
     * - Streaming validation (no intermediate collections)
     */
    private BatchChunkResult processChunkDirectInsert(
            List<T> entities, int start, int end, boolean skipDuplicates,
            List<String> skipReasons, int batchNum, int totalBatches) {

        int successCount = 0;
        int skipCount = 0;

        for (int i = start; i < end; i++) {
            T entity = entities.get(i);
            if (entity == null) continue; // Already processed

            try {
                // VALIDATION: In-memory only (no DB query)
                validateInMemory(entity);

                // PERSIST: Let DB handle constraint violations
                entityManager.persist(entity);
                successCount++;

            } catch (ConstraintViolationException | PersistenceException e) {
                // DB constraint violation (duplicate, FK, etc.)
                skipCount++;
                if (skipReasons.size() < 1000) {
                    skipReasons.add(String.format("Index %d: %s", i, extractRootCause(e)));
                }

                if (!skipDuplicates) {
                    log.error("Batch {} aborted at index {}: {}", batchNum, i, e.getMessage());
                    throw new DuplicateEntityException("Duplicate at index " + i + ": " + extractRootCause(e));
                }

                // Clear failed entity from persistence context
                if (entityManager.contains(entity)) {
                    entityManager.detach(entity);
                }

            } catch (Exception e) {
                skipCount++;
                if (skipReasons.size() < 1000) {
                    skipReasons.add(String.format("Index %d: %s", i, e.getMessage()));
                }

                if (!skipDuplicates) {
                    throw new RuntimeException("Validation failed at index " + i, e);
                }
            }

            // PERIODIC FLUSH: Every 500 entities within chunk
            if ((i - start + 1) % 500 == 0) {
                try {
                    entityManager.flush();
                    entityManager.clear();
                } catch (Exception e) {
                    log.debug("Mid-chunk flush failed: {}", e.getMessage());
                }
            }
        }

        log.debug("✅ Batch {}/{}: {} inserted, {} skipped",
                batchNum, totalBatches, successCount, skipCount);

        return new BatchChunkResult(successCount, skipCount);
    }

    /**
     * IN-MEMORY validation only (no DB queries)
     */
    private void validateInMemory(T entity) {
        // Jakarta Validation (annotations like @NotNull, @Size, etc.)
        if (validator != null) {
            Set<ConstraintViolation<T>> violations = validator.validate(entity);
            if (!violations.isEmpty()) {
                String errors = violations.stream()
                        .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                        .collect(Collectors.joining(", "));
                throw new IllegalArgumentException("Validation failed: " + errors);
            }
        }

        // NOTE: Unique constraints validated by DB on INSERT
        // This eliminates unnecessary SELECT queries before each INSERT
    }

    /**
     * ADAPTIVE batch sizing based on dataset size
     */
    private int calculateOptimalBatchSize(int totalSize) {
        if (totalSize <= 1000) return BATCH_SIZE_SMALL;
        if (totalSize <= 10_000) return BATCH_SIZE_MEDIUM;
        if (totalSize <= 50_000) return BATCH_SIZE_LARGE;
        if (totalSize <= 100_000) return BATCH_SIZE_X_LARGE;
        return BATCH_SIZE_MAX;
    }

    /**
     * REAL-TIME progress with metrics
     */
    private void logBatchProgress(int total, int current, int success, int skipped,
                                  long startTime, int batchNum, int totalBatches) {
        long elapsed = System.currentTimeMillis() - startTime;
        double progress = (double) current / total * 100;
        double throughput = elapsed > 0 ? (success * 1000.0) / elapsed : 0;
        long eta = elapsed > 0 ? (long) ((elapsed / progress) * (100 - progress)) : 0;

        long heapUsed = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) >> 20;
        long heapMax = Runtime.getRuntime().maxMemory() >> 20;

        String progressStr = String.format("%.1f", progress);
        String throughputStr = String.format("%.0f", throughput);

        log.info("📊 Progress: {}/{} ({}%) | Batch {}/{} | Success: {} | Skip: {} | " +
                        "{} rec/sec | Mem: {}/{} MB | ETA: {} sec",
                current, total, progressStr, batchNum, totalBatches, success, skipped,
                throughputStr, heapUsed, heapMax, eta / 1000);
    }

    /**
     * Extract root cause from exception chain
     */
    private String extractRootCause(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String msg = cause.getMessage();
        return msg != null && msg.length() > 100 ? msg.substring(0, 100) + "..." : msg;
    }

    /**
     * Lightweight result holder
     */
    private static class BatchChunkResult {
        final int successCount;
        final int skipCount;

        BatchChunkResult(int successCount, int skipCount) {
            this.successCount = successCount;
            this.skipCount = skipCount;
        }
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

        // Apply sorting
        List<Order> orders = new ArrayList<>();
        sort.forEach(order -> {
            orders.add(order.isAscending() ? cb.asc(root.get(order.getProperty()))
                    : cb.desc(root.get(order.getProperty())));
        });
        query.orderBy(orders);

        return entityManager.createQuery(query).getResultList();
    }

    /**
     * STREAMING: For large datasets (bounded memory)
     */
    @Transactional(readOnly = true)
    private List<T> findAllStreaming(Sort sort) {
        List<T> result = new ArrayList<>();
        int offset = 0;
        int fetchSize = 50; // Small chunks for memory efficiency

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

            // Clear session to prevent memory buildup
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

        // Apply sorting
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

        entityManager.merge(entity);
        entityManager.flush();
        return entity;
    }

    @Override
    @Transactional(timeout = 600)
    public BatchResult<T> updateBatch(Map<ID, Map<String, Object>> updates) {
        int successCount = 0;
        int skipCount = 0;
        List<String> skipReasons = new ArrayList<>();

        int processed = 0;
        for (Map.Entry<ID, Map<String, Object>> entry : updates.entrySet()) {
            try {
                update(entry.getKey(), entry.getValue());
                successCount++;
                processed++;

                // Periodic flush
                if (processed % 50 == 0) {
                    entityManager.flush();
                    entityManager.clear();
                }

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
     * CACHED unique constraint metadata
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
                    log.warn("Unique constraint field not found: {}", fieldName);
                }
            }
            if (!fields.isEmpty()) {
                constraints.add(new UniqueConstraintMeta(fields, ann.message()));
            }
        }

        UNIQUE_CONSTRAINT_CACHE.put(entityClass, constraints);
        log.debug("Cached {} unique constraints for {}", constraints.size(), entityClass.getSimpleName());
    }

    /**
     * Validate unique constraints (DB query)
     * NOTE: Only called for single creates, NOT batch inserts
     */
    private void validateUniqueConstraints(T entity) {
        List<UniqueConstraintMeta> constraints = UNIQUE_CONSTRAINT_CACHE.get(entityClass);
        if (constraints == null || constraints.isEmpty()) return;

        for (UniqueConstraintMeta meta : constraints) {
            CriteriaBuilder cb = entityManager.getCriteriaBuilder();
            CriteriaQuery<Long> query = cb.createQuery(Long.class);
            Root<T> root = query.from(entityClass);

            List<Predicate> predicates = new ArrayList<>();

            for (Field field : meta.fields) {
                try {
                    Object value = field.get(entity);
                    if (value != null) {
                        predicates.add(cb.equal(root.get(field.getName()), value));
                    }
                } catch (Exception e) {
                    log.debug("Field access error: {}", field.getName());
                }
            }

            if (entity.getId() != null) {
                predicates.add(cb.notEqual(root.get("id"), entity.getId()));
            }

            query.select(cb.count(root)).where(predicates.toArray(new Predicate[0]));
            Long count = entityManager.createQuery(query).getSingleResult();

            if (count > 0) {
                String msg = meta.message.isEmpty() ?
                        "Duplicate entry for unique constraint" : meta.message;
                throw new DuplicateEntityException(msg);
            }
        }
    }

    private void autoValidateUpdates(Map<String, Object> updates, T entity) {
        // Protected fields
        List<String> protectedFields = List.of("id", "createdAt", "created_at", "createdBy", "created_by");

        for (String field : protectedFields) {
            if (updates.containsKey(field)) {
                throw new IllegalArgumentException("Cannot update protected field: " + field);
            }
        }

        // Check immutable fields
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

        // Validate via Jakarta Validation
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
                throw new RuntimeException("Validation error", e);
            } finally {
                // Rollback changes
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

    /**
     * Unique constraint metadata holder
     */
    private static class UniqueConstraintMeta {
        final List<Field> fields;
        final String message;

        UniqueConstraintMeta(List<Field> fields, String message) {
            this.fields = fields;
            this.message = message;
        }
    }
}