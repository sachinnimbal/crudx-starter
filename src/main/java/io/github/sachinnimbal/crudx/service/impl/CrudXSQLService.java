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
    private static final int AGGRESSIVE_BATCH_SIZE = 50; // Smaller for memory
    private static final int MEMORY_CLEANUP_INTERVAL = 100; // GC hint every 100 batches

    @PostConstruct
    @SuppressWarnings("unchecked")
    protected void init() {
        if (entityManager == null) {
            throw new IllegalStateException(
                    "EntityManager not available. Please add 'spring-boot-starter-data-jpa' " +
                            "dependency and appropriate database driver (MySQL/PostgreSQL) to your project."
            );
        }
        Type genericSuperclass = getClass().getGenericSuperclass();
        if (genericSuperclass instanceof ParameterizedType) {
            Type[] typeArgs = ((ParameterizedType) genericSuperclass).getActualTypeArguments();
            if (typeArgs.length > 0 && typeArgs[0] instanceof Class) {
                entityClass = (Class<T>) typeArgs[0];
                log.debug("Entity class resolved: {}", entityClass.getSimpleName());
                return;
            }
        }

        Class<?>[] typeArgs = GenericTypeResolver.resolveTypeArguments(getClass(), CrudXSQLService.class);
        if (typeArgs != null && typeArgs.length > 0) {
            entityClass = (Class<T>) typeArgs[0];
            log.debug("Entity class resolved via GenericTypeResolver: {}", entityClass.getSimpleName());
            return;
        }
        if (entityManager != null) {
            entityManager.getEntityManagerFactory()
                    .getProperties()
                    .put("javax.persistence.query.timeout", 30000);
            entityManager.getEntityManagerFactory()
                    .getProperties()
                    .put("javax.persistence.lock.timeout", 10000);

            log.info("Query and lock timeouts configured for {}", entityClass.getSimpleName());
        }
        throw new IllegalStateException(
                "Could not resolve entity class for service: " + getClass().getSimpleName()
        );
    }

    @Override
    @Transactional(timeout = 300)
    public T create(T entity) {
        long startTime = System.currentTimeMillis();
        log.debug("Creating SQL entity: {}", getEntityClassName());
        validateUniqueConstraints(entity);
        entityManager.persist(entity);
        entityManager.flush();
        long duration = System.currentTimeMillis() - startTime;
        log.info("SQL entity created with ID: {} | Time taken: {} ms", entity.getId(), duration);
        return entity;
    }

    @Override
    @Transactional(timeout = 1800) // 30 minutes for very large batches
    public BatchResult<T> createBatch(List<T> entities, boolean skipDuplicates) {
        long startTime = System.currentTimeMillis();
        int totalSize = entities.size();

        log.info(" Starting batch creation: {} entities", totalSize);
        int batchSize = Math.min(AGGRESSIVE_BATCH_SIZE, crudxProperties.getBatchSize());
        int successCount = 0;
        int skipCount = 0;
        List<String> skipReasons = new ArrayList<>(Math.min(1000, totalSize / 10));

        int batchNumber = 0;
        int totalBatches = (totalSize + batchSize - 1) / batchSize;

        for (int i = 0; i < totalSize; i += batchSize) {
            batchNumber++;
            int end = Math.min(i + batchSize, totalSize);
            int batchSuccessCount = 0;

            for (int j = i; j < end; j++) {
                T entity = entities.get(j);

                try {
                    validateUniqueConstraints(entity);
                    entityManager.persist(entity);
                    batchSuccessCount++;
                    successCount++;

                } catch (DuplicateEntityException e) {
                    if (skipDuplicates) {
                        skipCount++;
                        if (skipReasons.size() < 1000) {
                            skipReasons.add(String.format("Index %d: %s", j, e.getMessage()));
                        }
                    } else {
                        log.error("Duplicate at index {} - aborting batch", j);
                        throw e;
                    }
                } catch (Exception e) {
                    if (skipDuplicates) {
                        skipCount++;
                        if (skipReasons.size() < 1000) {
                            skipReasons.add(String.format("Index %d: %s", j, e.getMessage()));
                        }
                    } else {
                        throw e;
                    }
                }

                entities.set(j, null);
            }

            if (batchSuccessCount > 0) {
                entityManager.flush();
                entityManager.clear();
            }

            if (batchNumber % MEMORY_CLEANUP_INTERVAL == 0) {
                System.gc();
            }
            if (batchNumber % 20 == 0 || batchNumber == totalBatches) {
                long currentMemory = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024;
                log.info("📊 Progress: {}/{} batches | Success: {} | Skipped: {} | Memory: {} MB",
                        batchNumber, totalBatches, successCount, skipCount, currentMemory);
            }
        }

        entities.clear();
        long duration = System.currentTimeMillis() - startTime;
        log.info("SQL entities created | Time taken: {} ms", duration);
        BatchResult<T> result = new BatchResult<>();
        result.setCreatedEntities(Collections.emptyList()); // Don't return full entities! only return first 50 entities just like /paged
        result.setSkippedCount(skipCount);
        result.setSkippedReasons(skipReasons);
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public T findById(ID id) {
        long startTime = System.currentTimeMillis();
        log.debug("Finding SQL entity by ID: {}", id);
        T entity = entityManager.find(entityClass, id);

        if (entity == null) {
            log.warn("Entity not found with ID: {}", id);
            throw new EntityNotFoundException(getEntityClassName(), id);
        }

        long duration = System.currentTimeMillis() - startTime;
        log.info("SQL entity found by ID: {} | Time taken: {} ms", id, duration);
        return entity;
    }

    @Override
    @Transactional(readOnly = true)
    public List<T> findAll() {
        long startTime = System.currentTimeMillis();
        long totalCount = count();

        log.debug("Finding all SQL entities (total: {})", totalCount);

        if (totalCount > MAX_IN_MEMORY_THRESHOLD) {
            log.warn("Large dataset detected ({} records). Loading with streaming", totalCount);
            return findAllWithStreaming(null);
        }

        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<T> query = cb.createQuery(entityClass);
        Root<T> root = query.from(entityClass);
        query.select(root);
        List<T> entities = entityManager.createQuery(query).getResultList();
        long duration = System.currentTimeMillis() - startTime;
        log.info("Found {} SQL entities | Time taken: {} ms", entities.size(), duration);
        return entities;
    }

    @Override
    @Transactional(readOnly = true)
    public List<T> findAll(Sort sort) {
        long startTime = System.currentTimeMillis();
        long totalCount = count();

        log.debug("Finding all SQL entities with sorting (total: {})", totalCount);

        if (totalCount > MAX_IN_MEMORY_THRESHOLD) {
            log.warn("Large dataset detected ({} records). Loading with streaming", totalCount);
            return findAllWithStreaming(sort);
        }

        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<T> query = cb.createQuery(entityClass);
        Root<T> root = query.from(entityClass);
        query.select(root);

        List<Order> orders = new ArrayList<>();
        sort.forEach(order -> {
            if (order.isAscending()) {
                orders.add(cb.asc(root.get(order.getProperty())));
            } else {
                orders.add(cb.desc(root.get(order.getProperty())));
            }
        });
        query.orderBy(orders);

        List<T> entities = entityManager.createQuery(query).getResultList();
        long duration = System.currentTimeMillis() - startTime;
        log.info("Found {} sorted SQL entities | Time taken: {} ms", entities.size(), duration);
        return entities;
    }

    @Transactional(readOnly = true)
    private List<T> findAllWithStreaming(Sort sort) {
        List<T> result = new ArrayList<>();
        int skip = 0;
        int batchSize = AGGRESSIVE_BATCH_SIZE;
        long totalCount = count();
        int batches = 0;

        while (true) {
            CriteriaBuilder cb = entityManager.getCriteriaBuilder();
            CriteriaQuery<T> query = cb.createQuery(entityClass);
            Root<T> root = query.from(entityClass);
            query.select(root);

            if (sort != null) {
                List<Order> orders = new ArrayList<>();
                sort.forEach(order -> {
                    if (order.isAscending()) {
                        orders.add(cb.asc(root.get(order.getProperty())));
                    } else {
                        orders.add(cb.desc(root.get(order.getProperty())));
                    }
                });
                query.orderBy(orders);
            }

            TypedQuery<T> typedQuery = entityManager.createQuery(query);
            typedQuery.setFirstResult(skip);
            typedQuery.setMaxResults(batchSize);

            List<T> batch = typedQuery.getResultList();

            if (batch.isEmpty()) {
                break;
            }

            result.addAll(batch);
            skip += batchSize;
            batches++;

            // 🔥 Clear session to prevent memory buildup
            entityManager.clear();

            // 🔥 Memory cleanup hint
            if (batches % MEMORY_CLEANUP_INTERVAL == 0) {
                System.gc();
            }

            log.debug("📊 Streamed batch {}: {}/{} entities", batches, result.size(), totalCount);

            if (batch.size() < batchSize) {
                break;
            }
        }

        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<T> findAll(Pageable pageable) {
        long startTime = System.currentTimeMillis();
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<T> query = cb.createQuery(entityClass);
        Root<T> root = query.from(entityClass);
        query.select(root);

        if (pageable.getSort().isSorted()) {
            List<Order> orders = new ArrayList<>();
            pageable.getSort().forEach(order -> {
                if (order.isAscending()) {
                    orders.add(cb.asc(root.get(order.getProperty())));
                } else {
                    orders.add(cb.desc(root.get(order.getProperty())));
                }
            });
            query.orderBy(orders);
        }

        List<T> entities = entityManager.createQuery(query)
                .setFirstResult((int) pageable.getOffset())
                .setMaxResults(pageable.getPageSize())
                .getResultList();

        long total = count();
        long duration = System.currentTimeMillis() - startTime;
        log.info("Found page of {} SQL entities (total: {}) | Time taken: {} ms",
                entities.size(), total, duration);
        return new PageImpl<>(entities, pageable, total);
    }

    @Override
    public T update(ID id, Map<String, Object> updates) {
        long startTime = System.currentTimeMillis();
        log.debug("Updating SQL entity with ID: {}", id);
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
            java.lang.reflect.Method onUpdateMethod = entityClass.getMethod("onUpdate");
            onUpdateMethod.invoke(entity);
        } catch (Exception e) {
            log.debug("No onUpdate method found", e);
        }

        entityManager.merge(entity);
        entityManager.flush();
        long duration = System.currentTimeMillis() - startTime;
        log.info("SQL entity updated with ID: {} | Time taken: {} ms", id, duration);
        return entity;
    }

    @Override
    public T delete(ID id) {
        long startTime = System.currentTimeMillis();
        log.debug("Deleting SQL entity with ID: {}", id);
        T entity = entityManager.find(entityClass, id);
        if (entity == null) {
            log.warn("Entity not found with ID: {}", id);
            throw new EntityNotFoundException(getEntityClassName(), id);
        }
        entityManager.remove(entity);
        entityManager.flush();
        long duration = System.currentTimeMillis() - startTime;
        log.info("SQL entity deleted with ID: {} | Time taken: {} ms", id, duration);
        return entity;
    }

    @Override
    @Transactional(readOnly = true)
    public long count() {
        long startTime = System.currentTimeMillis();
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> query = cb.createQuery(Long.class);
        Root<T> root = query.from(entityClass);
        query.select(cb.count(root));
        long count = entityManager.createQuery(query).getSingleResult();
        long duration = System.currentTimeMillis() - startTime;
        log.debug("SQL entity count: {} | Time taken: {} ms", count, duration);
        return count;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsById(ID id) {
        long startTime = System.currentTimeMillis();
        boolean exists = entityManager.find(entityClass, id) != null;
        long duration = System.currentTimeMillis() - startTime;
        log.info("SQL entity exists check for ID: {} | Result: {} | Time taken: {} ms",
                id, exists, duration);
        return exists;
    }

    @Override
    public BatchResult<T> deleteBatch(List<ID> ids) {
        long startTime = System.currentTimeMillis();
        log.debug("Deleting batch of {} SQL entities", ids.size());

        BatchResult<T> result = new BatchResult<>();
        List<ID> notFoundIds = new ArrayList<>();

        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<T> query = cb.createQuery(entityClass);
        Root<T> root = query.from(entityClass);
        query.select(root);
        query.where(root.get("id").in(ids));

        List<T> entitiesToDelete = entityManager.createQuery(query).getResultList();

        Set<ID> foundIds = entitiesToDelete.stream()
                .map(T::getId)
                .collect(Collectors.toSet());

        for (ID id : ids) {
            if (!foundIds.contains(id)) {
                notFoundIds.add(id);
                result.addSkippedReason(String.format("ID %s not found", id));
            }
        }
        result.setSkippedCount(notFoundIds.size());

        if (!entitiesToDelete.isEmpty()) {
            int batchSize = AGGRESSIVE_BATCH_SIZE;
            for (int i = 0; i < entitiesToDelete.size(); i += batchSize) {
                int end = Math.min(i + batchSize, entitiesToDelete.size());
                List<T> deleteBatch = entitiesToDelete.subList(i, end);

                for (T entity : deleteBatch) {
                    entityManager.remove(entity);
                }
                entityManager.flush();
                entityManager.clear();

                result.getCreatedEntities().addAll(deleteBatch);
                log.info("Deleted batch {}/{} SQL entities",
                        result.getCreatedEntities().size(), entitiesToDelete.size());
            }
        }

        long duration = System.currentTimeMillis() - startTime;
        log.info("Batch deletion completed: {} deleted, {} skipped | Time taken: {} ms",
                result.getCreatedEntities().size(), result.getSkippedCount(), duration);

        return result;
    }

    @Override
    @Transactional(timeout = 1800)
    public BatchResult<T> updateBatch(Map<ID, Map<String, Object>> updates) {
        long startTime = System.currentTimeMillis();
        log.debug("Updating batch of {} entities", updates.size());

        BatchResult<T> result = new BatchResult<>();
        int processedCount = 0;

        for (Map.Entry<ID, Map<String, Object>> entry : updates.entrySet()) {
            try {
                T updated = update(entry.getKey(), entry.getValue());
                result.getCreatedEntities().add(updated);
                processedCount++;

                if (processedCount % AGGRESSIVE_BATCH_SIZE == 0) {
                    entityManager.flush();
                    entityManager.clear();
                }

            } catch (Exception e) {
                result.setSkippedCount(result.getSkippedCount() + 1);
                result.addSkippedReason("ID " + entry.getKey() + ": " + e.getMessage());
                log.error("Failed to update entity {}: {}", entry.getKey(), e.getMessage());
            }
        }

        long duration = System.currentTimeMillis() - startTime;
        log.info("Batch update completed: {} updated, {} skipped | Time: {} ms",
                result.getCreatedEntities().size(), result.getSkippedCount(), duration);

        return result;
    }

    private void validateUniqueConstraints(T entity) {
        CrudXUniqueConstraint[] constraints = entityClass.getAnnotationsByType(CrudXUniqueConstraint.class);

        for (CrudXUniqueConstraint constraint : constraints) {
            CriteriaBuilder cb = entityManager.getCriteriaBuilder();
            CriteriaQuery<Long> query = cb.createQuery(Long.class);
            Root<T> root = query.from(entityClass);

            List<Predicate> predicates = new ArrayList<>();

            for (String fieldName : constraint.fields()) {
                try {
                    Field field = getFieldFromClass(entityClass, fieldName);
                    field.setAccessible(true);
                    Object value = field.get(entity);

                    if (value != null) {
                        predicates.add(cb.equal(root.get(fieldName), value));
                    }
                } catch (Exception e) {
                    log.error("Error accessing field: {}", fieldName, e);
                }
            }

            if (entity.getId() != null) {
                predicates.add(cb.notEqual(root.get("id"), entity.getId()));
            }

            query.select(cb.count(root));
            query.where(predicates.toArray(new Predicate[0]));

            Long count = entityManager.createQuery(query).getSingleResult();

            if (count > 0) {
                String message = constraint.message().isEmpty()
                        ? String.format("Duplicate entry found for fields: %s", String.join(", ", constraint.fields()))
                        : constraint.message();

                log.warn("Unique constraint violation: {}", message);
                throw new DuplicateEntityException(message);
            }
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

    private void autoValidateUpdates(Map<String, Object> updates, T entity) {
        List<String> autoProtectedFields = List.of(
                "id",
                "createdAt", "created_at",
                "createdBy", "created_by"
        );

        for (String protectedField : autoProtectedFields) {
            if (updates.containsKey(protectedField)) {
                throw new IllegalArgumentException(
                        "Cannot update protected field: " + protectedField
                );
            }
        }

        for (String fieldName : updates.keySet()) {
            try {
                Field field = getFieldFromClass(entityClass, fieldName);

                if (field.isAnnotationPresent(CrudXImmutable.class)) {
                    CrudXImmutable annotation = field.getAnnotation(CrudXImmutable.class);
                    throw new IllegalArgumentException(
                            String.format("Field '%s' is immutable: %s", fieldName, annotation.message())
                    );
                }

            } catch (NoSuchFieldException e) {
                throw new IllegalArgumentException("Field '" + fieldName + "' does not exist");
            }
        }

        Map<String, Object> oldValues = new HashMap<>();
        try {
            for (Map.Entry<String, Object> entry : updates.entrySet()) {
                Field field;
                try {
                    field = getFieldFromClass(entityClass, entry.getKey());
                } catch (NoSuchFieldException e) {
                    throw new RuntimeException(e);
                }
                field.setAccessible(true);
                oldValues.put(entry.getKey(), field.get(entity));
                field.set(entity, entry.getValue());
            }

            if (validator != null) {
                Set<ConstraintViolation<T>> violations = validator.validate(entity);
                if (!violations.isEmpty()) {
                    String errors = violations.stream()
                            .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                            .collect(Collectors.joining(", "));
                    throw new IllegalArgumentException("Validation failed: " + errors);
                }
            }

        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        } finally {
            oldValues.forEach((fieldName, oldValue) -> {
                try {
                    Field field = getFieldFromClass(entityClass, fieldName);
                    field.setAccessible(true);
                    field.set(entity, oldValue);
                } catch (Exception e) {
                    log.debug("Error rolling back", e);
                }
            });
        }
        validateUniqueConstraints(entity);
    }
}