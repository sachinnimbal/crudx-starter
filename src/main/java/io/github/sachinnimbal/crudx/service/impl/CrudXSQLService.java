/*
 * Copyright 2025 Sachin Nimbal
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.sachinnimbal.crudx.service.impl;

import io.github.sachinnimbal.crudx.core.annotations.CrudXImmutable;
import io.github.sachinnimbal.crudx.core.annotations.CrudXUniqueConstraint;
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

/**
 * @author Sachin Nimbal
 * @see <a href="https://www.linkedin.com/in/sachin-nimbal/">LinkedIn Profile</a>
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

    // Configurable default batch size - optimized for minimal memory usage
    private static final int DEFAULT_BATCH_SIZE = 100;  // Smaller batches for lower memory
    private static final int MAX_IN_MEMORY_THRESHOLD = 5000;  // Lower threshold

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

        throw new IllegalStateException(
                "Could not resolve entity class for service: " + getClass().getSimpleName()
        );
    }

    @Override
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
    public BatchResult<T> createBatch(List<T> entities, boolean skipDuplicates) {
        long startTime = System.currentTimeMillis();
        log.debug("Creating batch of {} SQL entities (skipDuplicates: {})", entities.size(), skipDuplicates);

        BatchResult<T> result = new BatchResult<>();
        int processedCount = 0;

        for (int i = 0; i < entities.size(); i++) {
            T entity = entities.get(i);

            try {
                // Validate unique constraints - will throw DuplicateEntityException if duplicate found
                validateUniqueConstraints(entity);

                entityManager.persist(entity);
                result.getCreatedEntities().add(entity);
                processedCount++;

                if (processedCount % DEFAULT_BATCH_SIZE == 0 || (i + 1) == entities.size()) {
                    long batchStartTime = System.currentTimeMillis();
                    entityManager.flush();
                    entityManager.clear();
                    long batchDuration = System.currentTimeMillis() - batchStartTime;
                    log.info("Flushed batch {}/{} | Created: {} | Skipped: {} | Flush time: {} ms",
                            i + 1, entities.size(),
                            result.getCreatedEntities().size(),
                            result.getSkippedCount(),
                            batchDuration);
                }
            } catch (DuplicateEntityException e) {
                if (skipDuplicates) {
                    // Skip this duplicate and continue
                    result.addSkippedReason(String.format("Entity at index %d skipped - %s", i, e.getMessage()));
                    result.setSkippedCount(result.getSkippedCount() + 1);
                    log.debug("Skipped duplicate entity at index {}: {}", i, e.getMessage());
                } else {
                    // Don't skip - throw exception to fail the entire batch
                    log.error("Duplicate entity found at index {} - failing batch creation", i);
                    throw e;
                }
            } catch (Exception e) {
                // For other exceptions, skip if skipDuplicates is true, otherwise throw
                if (skipDuplicates) {
                    result.addSkippedReason(String.format("Entity at index %d skipped - error: %s", i, e.getMessage()));
                    result.setSkippedCount(result.getSkippedCount() + 1);
                    log.error("Error processing entity at index {}, skipping: {}", i, e.getMessage());
                } else {
                    log.error("Error processing entity at index {} - failing batch creation", i);
                    throw e;
                }
            }
        }

        long totalDuration = System.currentTimeMillis() - startTime;
        double avgTimePerEntity = result.getTotalProcessed() > 0 ?
                (double) totalDuration / result.getTotalProcessed() : 0;

        log.info("Batch creation completed: {} created, {} skipped | Total time: {} ms | Avg time per entity: {} ms",
                result.getCreatedEntities().size(),
                result.getSkippedCount(),
                totalDuration,
                String.format("%.3f", avgTimePerEntity));

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

        // For large datasets, use cursor-based streaming with minimal memory
        if (totalCount > MAX_IN_MEMORY_THRESHOLD) {
            log.warn("Large dataset detected ({} records). Loading with minimal memory footprint using cursor streaming", totalCount);
            return findAllWithCursor(null);
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

        // For large datasets, use cursor-based streaming
        if (totalCount > MAX_IN_MEMORY_THRESHOLD) {
            log.warn("Large dataset detected ({} records). Loading with minimal memory footprint using cursor streaming", totalCount);
            return findAllWithCursor(sort);
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
    private List<T> findAllWithCursor(Sort sort) {
        List<T> result = new ArrayList<>();
        int firstResult = 0;
        int batchSize = DEFAULT_BATCH_SIZE;
        long totalCount = count();

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
            typedQuery.setFirstResult(firstResult);
            typedQuery.setMaxResults(batchSize);

            List<T> batch = typedQuery.getResultList();

            if (batch.isEmpty()) {
                break;
            }

            result.addAll(batch);
            firstResult += batchSize;

            // Critical: Clear EntityManager to free memory immediately
            entityManager.clear();

            // Clear batch reference to allow GC
            batch.clear();

            log.debug("Loaded batch: {}/{} entities (memory optimized)", result.size(), totalCount);

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

        // Fetch all entities in one query (optimized)
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<T> query = cb.createQuery(entityClass);
        Root<T> root = query.from(entityClass);
        query.select(root);
        query.where(root.get("id").in(ids));

        List<T> entitiesToDelete = entityManager.createQuery(query).getResultList();

        // Track which IDs were found
        Set<ID> foundIds = entitiesToDelete.stream()
                .map(T::getId)
                .collect(Collectors.toSet());

        // Track not found IDs
        for (ID id : ids) {
            if (!foundIds.contains(id)) {
                notFoundIds.add(id);
                result.addSkippedReason(String.format("ID %s not found", id));
            }
        }
        result.setSkippedCount(notFoundIds.size());

        // Delete in batches
        if (!entitiesToDelete.isEmpty()) {
            int batchSize = 100;
            for (int i = 0; i < entitiesToDelete.size(); i += batchSize) {
                int end = Math.min(i + batchSize, entitiesToDelete.size());
                List<T> deleteBatch = entitiesToDelete.subList(i, end);

                // Delete batch
                for (T entity : deleteBatch) {
                    entityManager.remove(entity);
                }
                entityManager.flush();

                // Add deleted entities to result
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

    /**
     * AUTO-VALIDATES using annotations
     */
    private void autoValidateUpdates(Map<String, Object> updates, T entity) {
        // 1. Smart default: Always protect these fields (zero config needed)
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

        // 2. Check for immutable fields and field existence
        for (String fieldName : updates.keySet()) {
            try {
                Field field = getFieldFromClass(entityClass, fieldName);

                // Auto-check @CrudXImmutable
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

        // 3. Apply updates temporarily for validation
        Map<String, Object> oldValues = new HashMap<>();
        try {
            for (Map.Entry<String, Object> entry : updates.entrySet()) {
                Field field = null;
                try {
                    field = getFieldFromClass(entityClass, entry.getKey());
                } catch (NoSuchFieldException e) {
                    throw new RuntimeException(e);
                }
                field.setAccessible(true);
                oldValues.put(entry.getKey(), field.get(entity));
                field.set(entity, entry.getValue());
            }

            // 4. Auto-validate using Jakarta Bean Validation (@Email, @Size, etc.)
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
            // Always rollback temporary changes
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
        // 5. Auto-check unique constraints
        validateUniqueConstraints(entity);
    }
}
