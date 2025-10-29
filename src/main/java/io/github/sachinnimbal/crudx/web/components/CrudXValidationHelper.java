package io.github.sachinnimbal.crudx.web.components;

import io.github.sachinnimbal.crudx.core.dto.annotations.CrudXField;
import io.github.sachinnimbal.crudx.core.model.CrudXBaseEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles all validation logic for CrudXController
 */
@Slf4j
public class CrudXValidationHelper<T extends CrudXBaseEntity<ID>, ID extends Serializable> {

    private static final int MAX_PAGE_SIZE = 100000;
    private final Class<T> entityClass;

    // Cache for required fields
    private final Map<String, Field> requiredFieldsCache = new ConcurrentHashMap<>();

    public CrudXValidationHelper(Class<T> entityClass) {
        this.entityClass = entityClass;
        cacheRequiredFields();
    }

    /**
     * Validate entity ID
     */
    public void validateId(ID id) {
        switch (id) {
            case null -> throw new IllegalArgumentException("ID cannot be null");
            case String s when s.trim().isEmpty() -> throw new IllegalArgumentException("ID cannot be empty");
            case Number number when number.longValue() <= 0 ->
                    throw new IllegalArgumentException("ID must be positive");
            default -> {
            }
        }
    }

    /**
     * Validate pagination parameters
     */
    public void validatePagination(int page, int size) {
        if (page < 0) {
            throw new IllegalArgumentException("Page number cannot be negative");
        }
        if (size <= 0) {
            throw new IllegalArgumentException("Page size must be greater than 0");
        }
        if (size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("Page size cannot exceed " + MAX_PAGE_SIZE);
        }
    }

    /**
     * Validate batch size
     */
    public void validateBatchSize(int size, int maxBatchSize) {
        if (size > maxBatchSize) {
            throw new IllegalArgumentException(
                    String.format("Batch size %d exceeds maximum allowed %d. " +
                                    "Please split your request into smaller batches.",
                            size, maxBatchSize)
            );
        }

        if (size < 2) {
            throw new IllegalArgumentException(
                    String.format("Batch creation requires at least 2 records. " +
                                    "Current size: %d. Use POST / endpoint for single record creation.",
                            size)
            );
        }
    }

    /**
     * Validate batch request body structure
     */
    public void validateBatchRequestBody(List<Map<String, Object>> requestBodies) {
        if (requestBodies == null || requestBodies.isEmpty()) {
            throw new IllegalArgumentException("Request body cannot be null or empty");
        }

        if (requestBodies.get(0) == null || requestBodies.get(0).isEmpty()) {
            throw new IllegalArgumentException(
                    "First record in batch is null or empty. All records must contain data."
            );
        }
    }

    /**
     * Validate required fields on an object
     */
    public void validateRequiredFields(Object obj) {
        if (obj == null || requiredFieldsCache.isEmpty()) {
            return;
        }

        try {
            for (Map.Entry<String, Field> entry : requiredFieldsCache.entrySet()) {
                Field field = entry.getValue();
                field.setAccessible(true);
                Object value = field.get(obj);

                if (value == null) {
                    throw new IllegalArgumentException(
                            "Required field '" + entry.getKey() + "' cannot be null"
                    );
                }
            }
        } catch (IllegalAccessException e) {
            log.warn("Field validation access error: {}", e.getMessage());
        }
    }

    /**
     * Validate request body is not null or empty
     */
    public void validateRequestBody(Map<String, Object> requestBody) {
        if (requestBody == null || requestBody.isEmpty()) {
            throw new IllegalArgumentException("Request body cannot be null or empty");
        }
    }

    /**
     * Validate updates map is not null or empty
     */
    public void validateUpdates(Map<String, Object> updates) {
        if (updates == null || updates.isEmpty()) {
            throw new IllegalArgumentException("Update data cannot be null or empty");
        }
    }

    /**
     * Validate batch updates map is not empty
     */
    public void validateBatchUpdates(Map<ID, Map<String, Object>> updates) {
        if (updates == null || updates.isEmpty()) {
            throw new IllegalArgumentException("Updates map cannot be empty");
        }
    }

    /**
     * Validate ID list is not empty
     */
    public void validateIdList(List<ID> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("ID list cannot be null or empty");
        }
    }

    /**
     * Validate force delete batch size
     */
    public void validateForceDeleteSize(int size, int threshold) {
        if (size > threshold) {
            throw new IllegalArgumentException(
                    String.format("Cannot force delete more than %d records. Current: %d IDs",
                            threshold, size)
            );
        }
    }

    /**
     * Validate sort direction
     */
    public void validateSortDirection(String sortDirection) {
        try {
            Sort.Direction.fromString(sortDirection);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Invalid sort direction: " + sortDirection + ". Must be ASC or DESC"
            );
        }
    }

    /**
     * Create pageable with validation
     */
    public Pageable createPageable(int page, int size, String sortBy, String sortDirection) {
        validatePagination(page, size);

        if (sortBy != null) {
            validateSortDirection(sortDirection);
            Sort.Direction direction = Sort.Direction.fromString(sortDirection);
            return PageRequest.of(page, size, Sort.by(direction, sortBy));
        }

        return PageRequest.of(page, size);
    }

    // ==================== PRIVATE METHODS ====================

    private void cacheRequiredFields() {
        try {
            for (Field field : getAllFields(entityClass)) {
                CrudXField annotation = field.getAnnotation(CrudXField.class);
                if (annotation != null && annotation.required()) {
                    requiredFieldsCache.put(field.getName(), field);
                }
            }

            log.debug("✓ Cached {} required fields for {}",
                    requiredFieldsCache.size(), entityClass.getSimpleName());

        } catch (Exception e) {
            log.warn("Failed to cache required fields: {}", e.getMessage());
        }
    }

    private Field[] getAllFields(Class<?> clazz) {
        List<Field> fields = new ArrayList<>();
        Class<?> current = clazz;

        while (current != null && current != Object.class) {
            Collections.addAll(fields, current.getDeclaredFields());
            current = current.getSuperclass();
        }

        return fields.toArray(new Field[0]);
    }
}