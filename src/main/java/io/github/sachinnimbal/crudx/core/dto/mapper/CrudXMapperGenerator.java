package io.github.sachinnimbal.crudx.core.dto.mapper;

import io.github.sachinnimbal.crudx.core.dto.annotations.CrudXField;
import io.github.sachinnimbal.crudx.core.dto.annotations.CrudXNested;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.lang.reflect.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime DTO mapper using reflection.
 * Generates mapping logic on-the-fly without code generation.
 *
 * @author Sachin Nimbal
 * @since 1.0.2
 */
@Slf4j
@Component
public class CrudXMapperGenerator {

    private final Map<String, DateTimeFormatter> formatters = new ConcurrentHashMap<>();
    private final Map<Class<?>, Constructor<?>> constructorCache = new ConcurrentHashMap<>();

    /**
     * Convert Request DTO to Entity.
     */
    public <E, R> E toEntity(R request, Class<E> entityClass) {
        if (request == null) {
            return null;
        }

        try {
            E entity = instantiate(entityClass);
            copyFields(request, entity, request.getClass(), entityClass, false);
            return entity;
        } catch (Exception e) {
            log.error("Failed to map {} to {}: {}",
                    request.getClass().getSimpleName(),
                    entityClass.getSimpleName(),
                    e.getMessage(), e);
            throw new RuntimeException("DTO mapping failed", e);
        }
    }

    /**
     * Update existing entity from Request DTO (partial update).
     */
    public <E, R> void updateEntity(R request, E entity) {
        if (request == null || entity == null) {
            return;
        }

        try {
            copyFields(request, entity, request.getClass(), entity.getClass(), true);
        } catch (Exception e) {
            log.error("Failed to update {} from {}: {}",
                    entity.getClass().getSimpleName(),
                    request.getClass().getSimpleName(),
                    e.getMessage(), e);
            throw new RuntimeException("DTO update failed", e);
        }
    }

    /**
     * Convert Entity to Response DTO.
     */
    public <E, S> S toResponse(E entity, Class<S> responseClass) {
        if (entity == null) {
            return null;
        }

        try {
            S response = instantiate(responseClass);
            copyFields(entity, response, entity.getClass(), responseClass, false);
            return response;
        } catch (Exception e) {
            log.error("Failed to map {} to {}: {}",
                    entity.getClass().getSimpleName(),
                    responseClass.getSimpleName(),
                    e.getMessage(), e);
            throw new RuntimeException("DTO mapping failed", e);
        }
    }

    /**
     * Convert list of entities to list of response DTOs.
     */
    public <E, S> List<S> toResponseList(List<E> entities, Class<S> responseClass) {
        if (entities == null) {
            return null;
        }

        List<S> responses = new ArrayList<>(entities.size());
        for (E entity : entities) {
            responses.add(toResponse(entity, responseClass));
        }
        return responses;
    }

    /**
     * Core field copying logic with nested object support.
     */
    private void copyFields(Object source, Object target,
                            Class<?> sourceClass, Class<?> targetClass,
                            boolean updateMode) throws Exception {

        Field[] targetFields = getAllFields(targetClass);

        for (Field targetField : targetFields) {
            if (Modifier.isStatic(targetField.getModifiers()) ||
                    Modifier.isFinal(targetField.getModifiers())) {
                continue;
            }

            // Check @CrudXField annotation
            CrudXField fieldAnnotation = targetField.getAnnotation(CrudXField.class);
            if (fieldAnnotation != null && fieldAnnotation.ignore()) {
                continue;
            }

            String sourceFieldName = getSourceFieldName(targetField, fieldAnnotation);
            Field sourceField = findField(sourceClass, sourceFieldName);

            if (sourceField == null) {
                // Field doesn't exist in source - skip or use default
                if (fieldAnnotation != null && !fieldAnnotation.defaultValue().isEmpty()) {
                    setFieldValue(target, targetField, fieldAnnotation.defaultValue());
                }
                continue;
            }

            sourceField.setAccessible(true);
            targetField.setAccessible(true);

            Object sourceValue = sourceField.get(source);

            // Skip null values in update mode
            if (updateMode && sourceValue == null) {
                continue;
            }

            // Handle nested objects
            if (targetField.isAnnotationPresent(CrudXNested.class)) {
                Object nestedValue = handleNestedObject(sourceValue, targetField);
                targetField.set(target, nestedValue);
                continue;
            }

            // Apply transformations
            Object transformedValue = transformValue(sourceValue, targetField, fieldAnnotation);
            targetField.set(target, transformedValue);
        }
    }

    /**
     * Handle nested objects and collections recursively.
     */
    private Object handleNestedObject(Object sourceValue, Field targetField) throws Exception {
        if (sourceValue == null) {
            CrudXNested nested = targetField.getAnnotation(CrudXNested.class);
            if (nested.nullStrategy() == CrudXNested.NullStrategy.EMPTY_COLLECTION) {
                if (List.class.isAssignableFrom(targetField.getType())) {
                    return new ArrayList<>();
                } else if (Set.class.isAssignableFrom(targetField.getType())) {
                    return new HashSet<>();
                }
            }
            return null;
        }

        CrudXNested nested = targetField.getAnnotation(CrudXNested.class);
        Class<?> targetType = nested.dtoClass();

        // Auto-detect DTO class from field type
        if (targetType == void.class) {
            targetType = getGenericType(targetField);
        }

        // Handle collections
        if (sourceValue instanceof Collection) {
            return mapCollection((Collection<?>) sourceValue, targetType, targetField.getType());
        }

        // Single nested object
        return toResponse(sourceValue, targetType);
    }

    /**
     * Map collection of entities to collection of DTOs.
     */
    @SuppressWarnings("unchecked")
    private Object mapCollection(Collection<?> source, Class<?> dtoClass, Class<?> collectionType)
            throws Exception {

        Collection<Object> result;

        if (List.class.isAssignableFrom(collectionType)) {
            result = new ArrayList<>(source.size());
        } else if (Set.class.isAssignableFrom(collectionType)) {
            result = new HashSet<>(source.size());
        } else {
            result = new ArrayList<>(source.size());
        }

        for (Object item : source) {
            result.add(toResponse(item, dtoClass));
        }

        return result;
    }

    /**
     * Transform value based on annotations and type conversions.
     */
    private Object transformValue(Object value, Field targetField, CrudXField fieldAnnotation) {
        if (value == null) {
            return null;
        }

        // Apply custom transformer if specified
        if (fieldAnnotation != null && !fieldAnnotation.transformer().isEmpty()) {
            value = applyTransformer(value, fieldAnnotation.transformer());
        }

        // Date formatting
        if (fieldAnnotation != null && !fieldAnnotation.format().isEmpty()) {
            if (value instanceof LocalDateTime) {
                DateTimeFormatter formatter = getFormatter(fieldAnnotation.format());
                return ((LocalDateTime) value).format(formatter);
            } else if (value instanceof LocalDate) {
                DateTimeFormatter formatter = getFormatter(fieldAnnotation.format());
                return ((LocalDate) value).format(formatter);
            }
        }

        // Type conversion
        return convertType(value, targetField.getType());
    }

    /**
     * Apply custom transformer method.
     */
    private Object applyTransformer(Object value, String transformerName) {
        try {
            // Built-in transformers
            return switch (transformerName) {
                case "toUpperCase" -> value.toString().toUpperCase();
                case "toLowerCase" -> value.toString().toLowerCase();
                case "trim" -> value.toString().trim();
                default -> value;
            };
        } catch (Exception e) {
            log.warn("Transformer '{}' failed: {}", transformerName, e.getMessage());
            return value;
        }
    }

    /**
     * Convert between compatible types.
     */
    private Object convertType(Object value, Class<?> targetType) {
        if (value == null || targetType.isAssignableFrom(value.getClass())) {
            return value;
        }

        try {
            // String conversions
            if (targetType == String.class) {
                return value.toString();
            }

            // Number conversions
            if (Number.class.isAssignableFrom(targetType) && value instanceof Number num) {
                if (targetType == Long.class || targetType == long.class) {
                    return num.longValue();
                } else if (targetType == Integer.class || targetType == int.class) {
                    return num.intValue();
                } else if (targetType == Double.class || targetType == double.class) {
                    return num.doubleValue();
                } else if (targetType == Float.class || targetType == float.class) {
                    return num.floatValue();
                }
            }

            // Enum conversions
            if (targetType.isEnum() && value instanceof String) {
                @SuppressWarnings({"unchecked", "rawtypes"})
                Object enumValue = Enum.valueOf((Class<Enum>) targetType, (String) value);
                return enumValue;
            }

            return value;
        } catch (Exception e) {
            log.debug("Type conversion failed: {} -> {}",
                    value.getClass().getSimpleName(),
                    targetType.getSimpleName());
            return value;
        }
    }

    /**
     * Get source field name from @CrudXField annotation or use target field name.
     */
    private String getSourceFieldName(Field targetField, CrudXField annotation) {
        if (annotation != null && !annotation.source().isEmpty()) {
            return annotation.source();
        }
        return targetField.getName();
    }

    /**
     * Find field in class hierarchy.
     */
    private Field findField(Class<?> clazz, String fieldName) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    /**
     * Get all fields including inherited ones.
     */
    private Field[] getAllFields(Class<?> clazz) {
        List<Field> fields = new ArrayList<>();
        Class<?> current = clazz;

        while (current != null && current != Object.class) {
            fields.addAll(Arrays.asList(current.getDeclaredFields()));
            current = current.getSuperclass();
        }

        return fields.toArray(new Field[0]);
    }

    /**
     * Get generic type from field (for collections).
     */
    private Class<?> getGenericType(Field field) {
        Type genericType = field.getGenericType();

        if (genericType instanceof ParameterizedType paramType) {
            Type[] typeArgs = paramType.getActualTypeArguments();

            if (typeArgs.length > 0 && typeArgs[0] instanceof Class) {
                return (Class<?>) typeArgs[0];
            }
        }

        return Object.class;
    }

    /**
     * Instantiate class using cached no-arg constructor.
     */
    @SuppressWarnings("unchecked")
    private <T> T instantiate(Class<T> clazz) throws Exception {
        Constructor<?> constructor = constructorCache.computeIfAbsent(clazz, c -> {
            try {
                Constructor<?> ctor = c.getDeclaredConstructor();
                ctor.setAccessible(true);
                return ctor;
            } catch (NoSuchMethodException e) {
                throw new RuntimeException("No no-arg constructor found for " + c.getSimpleName(), e);
            }
        });

        return (T) constructor.newInstance();
    }

    /**
     * Get or create date formatter.
     */
    private DateTimeFormatter getFormatter(String pattern) {
        return formatters.computeIfAbsent(pattern, DateTimeFormatter::ofPattern);
    }

    /**
     * Set field value with type conversion from string.
     */
    private void setFieldValue(Object target, Field field, String value) throws Exception {
        field.setAccessible(true);
        Class<?> type = field.getType();

        if (type == String.class) {
            field.set(target, value);
        } else if (type == Integer.class || type == int.class) {
            field.set(target, Integer.parseInt(value));
        } else if (type == Long.class || type == long.class) {
            field.set(target, Long.parseLong(value));
        } else if (type == Boolean.class || type == boolean.class) {
            field.set(target, Boolean.parseBoolean(value));
        } else if (type == Double.class || type == double.class) {
            field.set(target, Double.parseDouble(value));
        }
    }
}