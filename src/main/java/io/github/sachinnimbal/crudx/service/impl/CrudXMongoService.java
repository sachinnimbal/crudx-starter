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
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

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
public abstract class CrudXMongoService<T extends CrudXMongoEntity<ID>, ID extends Serializable>
        implements CrudXService<T, ID> {

    @Autowired
    protected MongoTemplate mongoTemplate;

    @Autowired(required = false)
    protected Validator validator;

    protected Class<T> entityClass;

    // Configurable default batch size - optimized for minimal memory usage
    private static final int DEFAULT_BATCH_SIZE = 100;  // Smaller batches for lower memory
    private static final int MAX_IN_MEMORY_THRESHOLD = 5000;  // Lower threshold

    @PostConstruct
    @SuppressWarnings("unchecked")
    protected void init() {
        if (mongoTemplate == null) {
            throw new IllegalStateException(
                    "MongoTemplate not available. Please add 'spring-boot-starter-data-mongodb' " +
                            "dependency to your project and configure MongoDB connection."
            );
        }
        Type genericSuperclass = getClass().getGenericSuperclass();
        if (genericSuperclass instanceof ParameterizedType) {
            Type[] typeArgs = ((ParameterizedType) genericSuperclass).getActualTypeArguments();
            if (typeArgs.length > 0 && typeArgs[0] instanceof Class) {
                entityClass = (Class<T>) typeArgs[0];
                log.debug("Entity class resolved via direct superclass: {}", entityClass.getSimpleName());
                return;
            }
        }

        Class<?>[] typeArgs = GenericTypeResolver.resolveTypeArguments(getClass(), CrudXMongoService.class);
        if (typeArgs != null && typeArgs.length > 0) {
            entityClass = (Class<T>) typeArgs[0];
            log.debug("Entity class resolved via GenericTypeResolver: {}", entityClass.getSimpleName());
            return;
        }

        throw new IllegalStateException(
                "Could not resolve entity class for service: " + getClass().getSimpleName() +
                        ". Ensure service properly extends CrudXMongoService with concrete type parameters."
        );
    }

    @Override
    public T create(T entity) {
        long startTime = System.currentTimeMillis();
        log.debug("Creating MongoDB entity: {}", getEntityClassName());

        validateUniqueConstraints(entity);
        entity.onCreate();

        T saved = mongoTemplate.save(entity);

        long duration = System.currentTimeMillis() - startTime;
        log.info("MongoDB entity created with ID: {} | Time taken: {} ms", saved.getId(), duration);
        return saved;
    }

    @Override
    public BatchResult<T> createBatch(List<T> entities, boolean skipDuplicates) {
        long startTime = System.currentTimeMillis();
        log.debug("Creating batch of {} MongoDB entities (skipDuplicates: {})", entities.size(), skipDuplicates);

        BatchResult<T> result = new BatchResult<>();
        int batchSize = DEFAULT_BATCH_SIZE;

        for (int i = 0; i < entities.size(); i += batchSize) {
            int end = Math.min(i + batchSize, entities.size());
            List<T> batchToProcess = entities.subList(i, end);
            List<T> validEntities = new ArrayList<>();

            // Validate each entity in the batch
            for (int j = 0; j < batchToProcess.size(); j++) {
                T entity = batchToProcess.get(j);
                int globalIndex = i + j;

                try {
                    // Validate unique constraints - will throw DuplicateEntityException if duplicate found
                    validateUniqueConstraints(entity);
                    entity.onCreate();
                    validEntities.add(entity);
                } catch (DuplicateEntityException e) {
                    if (skipDuplicates) {
                        // Skip this duplicate and continue
                        result.addSkippedReason(String.format("Entity at index %d skipped - %s", globalIndex, e.getMessage()));
                        result.setSkippedCount(result.getSkippedCount() + 1);
                        log.debug("Skipped duplicate entity at index {}: {}", globalIndex, e.getMessage());
                    } else {
                        // Don't skip - throw exception to fail the entire batch
                        log.error("Duplicate entity found at index {} - failing batch creation", globalIndex);
                        throw e;
                    }
                } catch (Exception e) {
                    // For other exceptions, skip if skipDuplicates is true, otherwise throw
                    if (skipDuplicates) {
                        result.addSkippedReason(String.format("Entity at index %d skipped - error: %s", globalIndex, e.getMessage()));
                        result.setSkippedCount(result.getSkippedCount() + 1);
                        log.error("Error processing entity at index {}, skipping: {}", globalIndex, e.getMessage());
                    } else {
                        log.error("Error processing entity at index {} - failing batch creation", globalIndex);
                        throw e;
                    }
                }
            }

            // Insert only valid entities
            if (!validEntities.isEmpty()) {
                long batchStartTime = System.currentTimeMillis();
                result.getCreatedEntities().addAll(mongoTemplate.insertAll(validEntities));
                long batchDuration = System.currentTimeMillis() - batchStartTime;
                log.info("Processed batch {}/{} | Created: {} | Skipped: {} | Time taken: {} ms",
                        end, entities.size(),
                        result.getCreatedEntities().size(),
                        result.getSkippedCount(),
                        batchDuration);
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
    public T findById(ID id) {
        long startTime = System.currentTimeMillis();
        log.debug("Finding MongoDB entity by ID: {}", id);
        T entity = mongoTemplate.findById(id, entityClass);

        if (entity == null) {
            log.warn("Entity not found with ID: {}", id);
            throw new EntityNotFoundException(getEntityClassName(), id);
        }

        long duration = System.currentTimeMillis() - startTime;
        log.info("MongoDB entity found by ID: {} | Time taken: {} ms", id, duration);
        return entity;
    }

    @Override
    public List<T> findAll() {
        long startTime = System.currentTimeMillis();
        long totalCount = count();

        log.debug("Finding all MongoDB entities (total: {})", totalCount);

        // For large datasets, use streaming instead of loading all at once
        if (totalCount > MAX_IN_MEMORY_THRESHOLD) {
            log.warn("Large dataset detected ({} records). Consider using streamAll() or pagination instead", totalCount);
            return findAllBatched(null);
        }

        List<T> entities = mongoTemplate.findAll(entityClass);
        long duration = System.currentTimeMillis() - startTime;
        log.info("Found {} MongoDB entities | Time taken: {} ms", entities.size(), duration);
        return entities;
    }

    @Override
    public List<T> findAll(Sort sort) {
        long startTime = System.currentTimeMillis();
        long totalCount = count();

        log.debug("Finding all MongoDB entities with sorting (total: {})", totalCount);

        // For large datasets, use streaming
        if (totalCount > MAX_IN_MEMORY_THRESHOLD) {
            log.warn("Large dataset detected ({} records). Consider using streamAll(sort, batchSize, processor) instead", totalCount);
            return findAllBatched(sort);
        }

        Query query = new Query().with(sort);
        List<T> entities = mongoTemplate.find(query, entityClass);
        long duration = System.currentTimeMillis() - startTime;
        log.info("Found {} sorted MongoDB entities | Time taken: {} ms", entities.size(), duration);
        return entities;
    }

    private List<T> findAllBatched(Sort sort) {
        List<T> allEntities = new ArrayList<>();
        int skip = 0;
        int batchSize = DEFAULT_BATCH_SIZE;

        while (true) {
            Query query = new Query().skip(skip).limit(batchSize);
            if (sort != null) {
                query.with(sort);
            }

            List<T> batch = mongoTemplate.find(query, entityClass);
            if (batch.isEmpty()) {
                break;
            }

            allEntities.addAll(batch);
            skip += batchSize;

            log.debug("Fetched batch: {}/{} entities", allEntities.size(), count());

            if (batch.size() < batchSize) {
                break;
            }
        }

        return allEntities;
    }

    @Override
    public Page<T> findAll(Pageable pageable) {
        long startTime = System.currentTimeMillis();
        log.debug("Finding paged MongoDB entities");
        Query query = new Query().with(pageable);

        List<T> entities = mongoTemplate.find(query, entityClass);
        long total = mongoTemplate.count(new Query(), entityClass);

        long duration = System.currentTimeMillis() - startTime;
        log.info("Found page of {} MongoDB entities (total: {}) | Time taken: {} ms",
                entities.size(), total, duration);
        return new PageImpl<>(entities, pageable, total);
    }

    @Override
    public T update(ID id, Map<String, Object> updates) {
        long startTime = System.currentTimeMillis();
        log.debug("Updating MongoDB entity with ID: {}", id);

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

        T updatedEntity = findById(id);
        long duration = System.currentTimeMillis() - startTime;
        log.info("MongoDB entity updated with ID: {} | Time taken: {} ms", id, duration);
        return updatedEntity;
    }

    @Override
    public T delete(ID id) {
        long startTime = System.currentTimeMillis();
        log.debug("Deleting MongoDB entity with ID: {}", id);
        Query query = new Query(Criteria.where("_id").is(id));
        T deletedEntity = mongoTemplate.findAndRemove(query, entityClass);
        if (deletedEntity == null) {
            log.warn("Entity not found with ID: {}", id);
            throw new EntityNotFoundException(getEntityClassName(), id);
        }
        long duration = System.currentTimeMillis() - startTime;
        log.info("MongoDB entity deleted with ID: {} | Time taken: {} ms", id, duration);
        return deletedEntity;
    }

    @Override
    public long count() {
        long startTime = System.currentTimeMillis();
        long count = mongoTemplate.count(new Query(), entityClass);
        long duration = System.currentTimeMillis() - startTime;
        log.debug("MongoDB entity count: {} | Time taken: {} ms", count, duration);
        return count;
    }

    @Override
    public boolean existsById(ID id) {
        long startTime = System.currentTimeMillis();
        boolean exists = mongoTemplate.exists(Query.query(Criteria.where("_id").is(id)), entityClass);
        long duration = System.currentTimeMillis() - startTime;
        log.info("MongoDB entity exists check for ID: {} | Result: {} | Time taken: {} ms",
                id, exists, duration);
        return exists;
    }

    @Override
    public BatchResult<T> deleteBatch(List<ID> ids) {
        long startTime = System.currentTimeMillis();
        log.debug("Deleting batch of {} MongoDB entities", ids.size());
        BatchResult<T> result = new BatchResult<>();
        // Fetch all entities in one query
        Query findQuery = Query.query(Criteria.where("_id").in(ids));
        List<T> entitiesToDelete = mongoTemplate.find(findQuery, entityClass);
        // Track which IDs were found
        Set<ID> foundIds = entitiesToDelete.stream()
                .map(T::getId)
                .collect(Collectors.toSet());
        // Track not found IDs
        for (ID id : ids) {
            if (!foundIds.contains(id)) {
                result.addSkippedReason(String.format("ID %s not found", id));
            }
        }
        result.setSkippedCount(ids.size() - foundIds.size());
        // Delete all found entities
        if (!entitiesToDelete.isEmpty()) {
            Query deleteQuery = Query.query(Criteria.where("_id").in(foundIds));
            mongoTemplate.remove(deleteQuery, entityClass);
            // Add deleted entities to result
            result.getCreatedEntities().addAll(entitiesToDelete);
        }
        long duration = System.currentTimeMillis() - startTime;
        log.info("Batch deletion completed: {} deleted, {} skipped | Time taken: {} ms",
                result.getCreatedEntities().size(), result.getSkippedCount(), duration);

        return result;
    }

    private void validateUniqueConstraints(T entity) {
        CrudXUniqueConstraint[] constraints = entityClass.getAnnotationsByType(CrudXUniqueConstraint.class);
        for (CrudXUniqueConstraint constraint : constraints) {
            Query query = new Query();

            for (String fieldName : constraint.fields()) {
                try {
                    Field field = getFieldFromClass(entityClass, fieldName);
                    field.setAccessible(true);
                    Object value = field.get(entity);

                    if (value != null) {
                        query.addCriteria(Criteria.where(fieldName).is(value));
                    }
                } catch (Exception e) {
                    log.error("Error accessing field: {}", fieldName, e);
                }
            }

            if (entity.getId() != null) {
                query.addCriteria(Criteria.where("_id").ne(entity.getId()));
            }

            if (mongoTemplate.exists(query, entityClass)) {
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
        // 1. Smart default: Always protect these fields
        List<String> autoProtectedFields = List.of(
                "_id", "id",
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

        // 2. Check immutable fields and field existence
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

        // 3. Apply updates temporarily
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

            // 4. Validate using Bean Validation
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
            // Rollback temporary changes
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
        // 5. Check unique constraints
        validateUniqueConstraints(entity);
    }
}
