package io.github.sachinnimbal.crudx.service.impl;

import io.github.sachinnimbal.crudx.core.annotations.CrudXImmutable;
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
import java.util.stream.Collectors;

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

        if (mongoTemplate != null) {
            try {
                // Validate MongoDB connection
                mongoTemplate.executeCommand("{ ping: 1 }");
                log.info("MongoDB connection validated for {}", entityClass.getSimpleName());
            } catch (Exception e) {
                log.error("MongoDB connection validation failed", e);
                throw new IllegalStateException("Cannot connect to MongoDB", e);
            }
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
    @Transactional(timeout = 1800)
    public BatchResult<T> createBatch(List<T> entities, boolean skipDuplicates) {
        long startTime = System.currentTimeMillis();
        int totalSize = entities.size();
        log.debug("Creating batch of {} MongoDB entities (skipDuplicates: {})", totalSize, skipDuplicates);

        // 🔥 Use smaller batch size
        int batchSize = Math.min(50, crudxProperties.getBatchSize());

        // 🔥 CRITICAL: Counters only, no result collection
        int successCount = 0;
        int skipCount = 0;
        List<String> skipReasons = new ArrayList<>(Math.min(1000, totalSize / 10));

        int batchNumber = 0;
        int totalBatches = (totalSize + batchSize - 1) / batchSize;

        for (int i = 0; i < totalSize; i += batchSize) {
            batchNumber++;
            int end = Math.min(i + batchSize, totalSize);

            List<T> validEntities = new ArrayList<>(batchSize);

            for (int j = i; j < end; j++) {
                T entity = entities.get(j);

                try {
                    validateUniqueConstraints(entity);
                    entity.onCreate();
                    validEntities.add(entity);

                } catch (DuplicateEntityException e) {
                    if (skipDuplicates) {
                        skipCount++;
                        if (skipReasons.size() < 1000) {
                            skipReasons.add(String.format("Index %d: %s", j, e.getMessage()));
                        }
                    } else {
                        throw e;
                    }
                }

                // 🔥 NULL OUT to free memory
                entities.set(j, null);
            }

            if (!validEntities.isEmpty()) {
                mongoTemplate.insertAll(validEntities);
                successCount += validEntities.size();
            }

            // 🔥 Clear batch
            validEntities.clear();

            // 🔥 Memory cleanup hint
            if (batchNumber % 100 == 0) {
                System.gc();
            }

            if (batchNumber % 20 == 0 || batchNumber == totalBatches) {
                long currentMemory = (Runtime.getRuntime().totalMemory() -
                        Runtime.getRuntime().freeMemory()) / 1024 / 1024;
                log.info("📊 Progress: {}/{} batches | Success: {} | Skipped: {} | Memory: {} MB",
                        batchNumber, totalBatches, successCount, skipCount, currentMemory);
            }
        }

        // 🔥 Clear input list
        entities.clear();

        long totalDuration = System.currentTimeMillis() - startTime;
        double recordsPerSecond = (successCount * 1000.0) / totalDuration;

        log.info("✅ MongoDB batch completed: {} created, {} skipped | {} rec/sec | {} ms",
                successCount, skipCount, String.format("%.0f", recordsPerSecond), totalDuration);

        // 🔥 Return lightweight result
        BatchResult<T> result = new BatchResult<>();
        result.setCreatedEntities(Collections.emptyList());
        result.setSkippedCount(skipCount);
        result.setSkippedReasons(skipReasons);

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
        int batchSize = crudxProperties.getBatchSize();

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

    @Override
    @Transactional(timeout = 600)
    public BatchResult<T> updateBatch(Map<ID, Map<String, Object>> updates) {
        long startTime = System.currentTimeMillis();
        log.debug("Updating batch of {} MongoDB entities", updates.size());

        BatchResult<T> result = new BatchResult<>();
        int processedCount = 0;
        int batchSize = crudxProperties.getBatchSize();

        // Process updates in batches for better performance
        List<Map.Entry<ID, Map<String, Object>>> updatesList = new ArrayList<>(updates.entrySet());

        for (int i = 0; i < updatesList.size(); i++) {
            Map.Entry<ID, Map<String, Object>> entry = updatesList.get(i);

            // Find the entity first (throws EntityNotFoundException if not found)
            T entity = findById(entry.getKey());

            // Validate updates (throws IllegalArgumentException if validation fails)
            autoValidateUpdates(entry.getValue(), entity);

            // Build update query
            Query query = new Query(Criteria.where("_id").is(entry.getKey()));
            Update update = new Update();

            // Apply field updates
            entry.getValue().forEach((key, value) -> {
                if (!"id".equals(key) && !"_id".equals(key)) {
                    update.set(key, value);
                }
            });

            // Update audit timestamp
            entity.onUpdate();
            update.set("audit.updatedAt", entity.getAudit().getUpdatedAt());

            // Execute update
            mongoTemplate.updateFirst(query, update, entityClass);

            // Fetch updated entity
            T updatedEntity = findById(entry.getKey());
            result.getCreatedEntities().add(updatedEntity);
            processedCount++;

            // Log progress every batch
            if (processedCount % batchSize == 0 || (i + 1) == updatesList.size()) {
                log.info("Processed batch {}/{} | Updated: {}",
                        i + 1, updatesList.size(),
                        result.getCreatedEntities().size());
            }
        }

        long totalDuration = System.currentTimeMillis() - startTime;
        double avgTimePerEntity = result.getTotalProcessed() > 0 ?
                (double) totalDuration / result.getTotalProcessed() : 0;

        log.info("Batch update completed: {} updated | Total time: {} ms | Avg time per entity: {} ms",
                result.getCreatedEntities().size(),
                totalDuration,
                String.format("%.3f", avgTimePerEntity));

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
