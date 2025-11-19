package io.github.sachinnimbal.crudx.service.impl.sql;

import io.github.sachinnimbal.crudx.core.annotations.CrudXImmutable;
import io.github.sachinnimbal.crudx.core.exception.DuplicateEntityException;
import io.github.sachinnimbal.crudx.core.model.CrudXBaseEntity;
import io.github.sachinnimbal.crudx.core.response.BatchResult;
import io.github.sachinnimbal.crudx.service.impl.sql.helper.CrudXSQLValidationHelper;
import jakarta.persistence.EntityManager;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Optimized SQL update operations
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CrudXSQLUpdateOperations<T extends CrudXBaseEntity<ID>, ID extends Serializable> {

    private final EntityManager entityManager;
    private final Validator validator;
    private final CrudXSQLValidationHelper<T, ID> validationHelper;
    private final CrudXSQLReadOperations<T, ID> readOperations;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public T update(ID id, Map<String, Object> updates, Class<T> entityClass) {
        T entity = readOperations.findById(id, entityClass);
        autoValidateUpdates(updates, entity, entityClass);

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

        // Validate unique constraints
        if (validationHelper.violatesUniqueConstraints(entity, entityClass, entityManager)) {
            throw new DuplicateEntityException(validationHelper.buildDuplicateMessage(entity, entityClass));
        }

        entityManager.merge(entity);
        entityManager.flush();
        return entity;
    }

    @Transactional(timeout = 600, isolation = Isolation.READ_COMMITTED)
    public BatchResult<T> updateBatch(Map<ID, Map<String, Object>> updates, Class<T> entityClass) {
        int successCount = 0;
        int skipCount = 0;
        int duplicateSkipCount = 0;
        List<String> skipReasons = new ArrayList<>();

        int processed = 0;
        for (Map.Entry<ID, Map<String, Object>> entry : updates.entrySet()) {
            try {
                update(entry.getKey(), entry.getValue(), entityClass);
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

    /**
     * Auto-validate updates using annotations and DB checks
     */
    private void autoValidateUpdates(Map<String, Object> updates, T entity, Class<T> entityClass) {
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
}