package io.github.sachinnimbal.crudx.service.impl.mongo;

import io.github.sachinnimbal.crudx.core.annotations.CrudXImmutable;
import io.github.sachinnimbal.crudx.core.exception.DuplicateEntityException;
import io.github.sachinnimbal.crudx.core.model.CrudXMongoEntity;
import io.github.sachinnimbal.crudx.core.response.BatchResult;
import io.github.sachinnimbal.crudx.service.impl.mongo.helper.CrudXMongoValidationHelper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class CrudXMongoUpdateOperations<T extends CrudXMongoEntity<ID>, ID extends Serializable> {

    private final MongoTemplate mongoTemplate;
    private final Validator validator;
    private final CrudXMongoValidationHelper<T, ID> validationHelper;
    private final CrudXMongoReadOperations<T, ID> readOperations;

    @Transactional(timeout = 300, isolation = Isolation.READ_COMMITTED)
    public T update(ID id, Map<String, Object> updates, Class<T> entityClass) {
        T entity = readOperations.findById(id, entityClass);
        autoValidateUpdates(updates, entity, entityClass);

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

        // Validate unique constraints
        if (validationHelper.violatesUniqueConstraints(entity, entityClass, mongoTemplate)) {
            throw new DuplicateEntityException(
                    validationHelper.buildDuplicateMessage(entity, entityClass));
        }

        Query query = new Query(Criteria.where("_id").is(id));
        Update update = new Update();

        updates.forEach((key, value) -> {
            if (!"id".equals(key) && !"_id".equals(key)) {
                update.set(key, value);
            }
        });
        update.set("audit.updatedAt", entity.getAudit().getUpdatedAt());

        mongoTemplate.updateFirst(query, update, entityClass);
        return readOperations.findById(id, entityClass);
    }

    @Transactional(timeout = 600)
    public BatchResult<T> updateBatch(Map<ID, Map<String, Object>> updates, Class<T> entityClass) {
        int successCount = 0;
        int skipCount = 0;
        int duplicateSkipCount = 0;
        List<String> skipReasons = new ArrayList<>();

        for (Map.Entry<ID, Map<String, Object>> entry : updates.entrySet()) {
            try {
                update(entry.getKey(), entry.getValue(), entityClass);
                successCount++;

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

    private void autoValidateUpdates(Map<String, Object> updates, T entity, Class<T> entityClass) {
        List<String> protectedFields = List.of("id", "_id", "createdAt", "created_at", "createdBy", "created_by");

        for (String field : protectedFields) {
            if (updates.containsKey(field)) {
                throw new IllegalArgumentException("Cannot update protected field: " + field);
            }
        }

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

        Map<String, Object> oldValues = new HashMap<>();
        try {
            for (Map.Entry<String, Object> entry : updates.entrySet()) {
                Field field = getFieldFromClass(entityClass, entry.getKey());
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

        } catch (IllegalAccessException | NoSuchFieldException e) {
            throw new RuntimeException("Validation error", e);
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
}
