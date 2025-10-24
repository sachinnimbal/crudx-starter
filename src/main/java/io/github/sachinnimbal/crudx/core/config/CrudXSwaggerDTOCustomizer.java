package io.github.sachinnimbal.crudx.core.config;

import io.github.sachinnimbal.crudx.core.dto.annotations.CrudXRequest;
import io.github.sachinnimbal.crudx.core.dto.mapper.CrudXMapperRegistry;
import io.github.sachinnimbal.crudx.core.enums.CrudXOperation;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;

/**
 * Enhanced Swagger customizer with proper audit field handling.
 *
 * @author Sachin Nimbal
 * @since 1.0.2
 */
@Slf4j
public class CrudXSwaggerDTOCustomizer implements OperationCustomizer {

    private final CrudXMapperRegistry dtoRegistry;
    private static final int MAX_DEPTH = 5;

    // Define audit fields that can be user-provided
    private static final Set<String> USER_PROVIDABLE_AUDIT_FIELDS = Set.of("createdBy", "updatedBy");

    // Define audit fields that are system-managed
    private static final Set<String> SYSTEM_AUDIT_FIELDS = Set.of("createdAt", "updatedAt");

    public CrudXSwaggerDTOCustomizer(CrudXMapperRegistry dtoRegistry) {
        this.dtoRegistry = dtoRegistry;
    }

    @Override
    public Operation customize(Operation operation, HandlerMethod handlerMethod) {
        Method method = handlerMethod.getMethod();
        String methodName = method.getName();

        Class<?> entityClass = extractEntityClass(handlerMethod);
        if (entityClass == null) {
            return operation;
        }

        log.debug("Customizing Swagger for {}.{} (entity: {})",
                handlerMethod.getBeanType().getSimpleName(),
                methodName,
                entityClass.getSimpleName());

        CrudXOperation crudOperation = detectOperation(methodName);

        if (crudOperation != null) {
            if (operation.getRequestBody() != null) {
                updateRequestBodySchema(operation, entityClass, crudOperation, methodName);
            }
            updateResponseSchema(operation, entityClass, crudOperation, methodName);
        }

        return operation;
    }

    private void updateRequestBodySchema(Operation operation, Class<?> entityClass,
                                         CrudXOperation crudOperation, String methodName) {
        RequestBody requestBody = operation.getRequestBody();
        if (requestBody == null || requestBody.getContent() == null) {
            return;
        }

        Content content = requestBody.getContent();
        MediaType mediaType = content.get("application/json");
        if (mediaType == null) return;

        Optional<Class<?>> requestDtoClass = dtoRegistry != null ?
                dtoRegistry.getRequestDTO(entityClass, crudOperation) : Optional.empty();

        Class<?> schemaClass = requestDtoClass.orElse(entityClass);
        boolean usingDto = requestDtoClass.isPresent();

        // 🔧 Get annotation from the ACTUAL DTO class (or entity if no DTO)
        CrudXRequest annotation = schemaClass.getAnnotation(CrudXRequest.class);

        // Determine filtering flags
        boolean excludeAudit;
        boolean excludeImmutable;

        if (annotation != null) {
            // Use explicit annotation values
            excludeAudit = annotation.excludeAudit();
            excludeImmutable = annotation.excludeImmutable();
            log.debug("✓ Request DTO annotation found: excludeAudit={}, excludeImmutable={}",
                    excludeAudit, excludeImmutable);
        } else {
            // Default behavior when no annotation (using entity directly)
            excludeAudit = false;  // Show all fields by default
            excludeImmutable = false;
            log.debug("✓ No @CrudXRequest annotation, using defaults: excludeAudit=false, excludeImmutable=false");
        }

        log.debug("✓ Using {} schema: {} for {} (excludeAudit={}, excludeImmutable={})",
                usingDto ? "Request DTO" : "Entity",
                schemaClass.getSimpleName(),
                crudOperation,
                excludeAudit,
                excludeImmutable);

        // Generate schema with filtering
        Schema<?> schema;
        if (isBatchOperation(methodName)) {
            schema = new ArraySchema().items(
                    generateCompleteSchema(schemaClass, entityClass, new HashSet<>(), 0,
                            excludeAudit, excludeImmutable, true));
        } else {
            schema = generateCompleteSchema(schemaClass, entityClass, new HashSet<>(), 0,
                    excludeAudit, excludeImmutable, true);
        }

        mediaType.setSchema(schema);
    }

    private void updateResponseSchema(Operation operation, Class<?> entityClass,
                                      CrudXOperation crudOperation, String methodName) {
        if (operation.getResponses() == null) return;

        Optional<Class<?>> responseDtoClass = dtoRegistry != null ?
                dtoRegistry.getResponseDTO(entityClass, crudOperation) : Optional.empty();

        Class<?> schemaClass = responseDtoClass.orElse(entityClass);
        boolean usingDto = responseDtoClass.isPresent();

        log.debug("✓ Using {} schema: {} for {}", usingDto ? "Response DTO" : "Entity",
                schemaClass.getSimpleName(), crudOperation);

        // Response DTOs don't need audit filtering in schema
        for (Map.Entry<String, ApiResponse> entry : operation.getResponses().entrySet()) {
            String statusCode = entry.getKey();

            if ("200".equals(statusCode) || "201".equals(statusCode)) {
                ApiResponse apiResponse = entry.getValue();
                Content content = apiResponse.getContent();

                if (content != null) {
                    MediaType mediaType = content.get("application/json");

                    if (mediaType != null) {
                        Schema<?> wrappedSchema;
                        if (isBatchOperation(methodName) || isGetAllOperation(methodName)) {
                            wrappedSchema = createApiResponseSchema(schemaClass, entityClass, true);
                        } else {
                            wrappedSchema = createApiResponseSchema(schemaClass, entityClass, false);
                        }

                        mediaType.setSchema(wrappedSchema);
                    }
                }
            }
        }
    }

    /**
     * Generate complete schema with audit and immutable field filtering.
     */
    private Schema<?> generateCompleteSchema(Class<?> schemaClass, Class<?> entityClass,
                                             Set<Class<?>> visited, int depth,
                                             boolean excludeAudit, boolean excludeImmutable,
                                             boolean isRequest) {
        if (visited.contains(schemaClass)) {
            log.debug("Circular reference detected for {}, using $ref", schemaClass.getSimpleName());
            Schema<?> refSchema = new Schema<>();
            refSchema.set$ref("#/components/schemas/" + schemaClass.getSimpleName());
            return refSchema;
        }

        if (depth > MAX_DEPTH) {
            log.debug("Maximum depth {} reached for {}, using $ref", MAX_DEPTH, schemaClass.getSimpleName());
            Schema<?> refSchema = new Schema<>();
            refSchema.set$ref("#/components/schemas/" + schemaClass.getSimpleName());
            return refSchema;
        }

        try {
            Set<Class<?>> newVisited = new HashSet<>(visited);
            newVisited.add(schemaClass);

            Schema<?> schema = new Schema<>();
            schema.setType("object");
            schema.setName(schemaClass.getSimpleName());

            Map<String, Schema> properties = new LinkedHashMap<>();
            List<Field> allFields = getAllFields(schemaClass);

            for (Field field : allFields) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    continue;
                }

                String fieldName = field.getName();

                // 🔧 KEY FIX: Only filter if isRequest AND filtering is enabled
                if (isRequest && excludeAudit && shouldExcludeAuditField(fieldName, entityClass)) {
                    log.debug("Excluding audit field '{}' from schema (excludeAudit=true)", fieldName);
                    continue;
                }

                if (isRequest && excludeImmutable && isFieldImmutable(field, entityClass, fieldName)) {
                    log.debug("Excluding immutable field '{}' from schema (excludeImmutable=true)", fieldName);
                    continue;
                }

                // 🔧 NEW: Show audit fields from entity when excludeAudit=false
                if (isRequest && !excludeAudit) {
                    // Check if this field needs to be fetched from entity's audit object
                    Field entityField = findFieldInHierarchy(entityClass, fieldName);
                    if (entityField == null && isAuditFieldName(fieldName)) {
                        // Field might be in entity's audit object
                        Field auditField = findFieldInHierarchy(entityClass, "audit");
                        if (auditField != null) {
                            Field auditSubField = findFieldInHierarchy(auditField.getType(), fieldName);
                            if (auditSubField != null) {
                                log.debug("✓ Including audit field '{}' from entity.audit (excludeAudit=false)",
                                        fieldName);
                                Schema<?> fieldSchema = generateFieldSchema(auditSubField, newVisited,
                                        depth + 1, excludeAudit, excludeImmutable, isRequest);
                                properties.put(fieldName, fieldSchema);
                                continue;
                            }
                        }
                    }
                }

                Schema<?> fieldSchema = generateFieldSchema(field, newVisited, depth + 1,
                        excludeAudit, excludeImmutable, isRequest);
                properties.put(fieldName, fieldSchema);
            }

            schema.setProperties(properties);
            return schema;

        } catch (Exception e) {
            log.warn("Failed to generate complete schema for {}: {}",
                    schemaClass.getSimpleName(), e.getMessage());
            return new Schema<>().type("object").name(schemaClass.getSimpleName());
        }
    }

    /**
     * Check if field name is an audit field name.
     */
    private boolean isAuditFieldName(String fieldName) {
        return USER_PROVIDABLE_AUDIT_FIELDS.contains(fieldName) ||
                SYSTEM_AUDIT_FIELDS.contains(fieldName);
    }

    /**
     * Determine if an audit field should be excluded from schema.
     * ⚠️ This is only called when excludeAudit = true
     */
    private boolean shouldExcludeAuditField(String fieldName, Class<?> entityClass) {
        // Never exclude user-providable fields
        if (USER_PROVIDABLE_AUDIT_FIELDS.contains(fieldName)) {
            log.debug("Keeping user-providable audit field: {}", fieldName);
            return false;
        }

        // Exclude system-managed fields
        if (SYSTEM_AUDIT_FIELDS.contains(fieldName)) {
            return true;
        }

        // Exclude the "audit" object itself if it exists
        if ("audit".equals(fieldName)) {
            Field auditField = findFieldInHierarchy(entityClass, "audit");
            if (auditField != null && isCrudXAuditType(auditField.getType())) {
                return true;
            }
        }

        return false;
    }

    /**
     * Check if a field is marked as immutable.
     */
    private boolean isFieldImmutable(Field dtoField, Class<?> entityClass, String fieldName) {
        // Always exclude 'id' field when excludeImmutable = true
        if ("id".equals(fieldName)) {
            return true;
        }

        // Check DTO field for @CrudXImmutable
        if (hasImmutableAnnotation(dtoField)) {
            return true;
        }

        // Check corresponding entity field
        Field entityField = findFieldInHierarchy(entityClass, fieldName);
        if (entityField != null && hasImmutableAnnotation(entityField)) {
            return true;
        }

        return false;
    }

    @SuppressWarnings("unchecked")
    private boolean hasImmutableAnnotation(Field field) {
        try {
            Class<?> immutableAnnotation = Class.forName(
                    "io.github.sachinnimbal.crudx.core.annotations.CrudXImmutable");
            return field.isAnnotationPresent(
                    (Class<? extends java.lang.annotation.Annotation>) immutableAnnotation);
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private Field findFieldInHierarchy(Class<?> clazz, String fieldName) {
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

    private boolean isCrudXAuditType(Class<?> type) {
        if (type == null) return false;
        if (type.getName().endsWith(".CrudXAudit")) return true;
        return isCrudXAuditType(type.getSuperclass());
    }

    /**
     * Generate field schema with filtering support.
     */
    private Schema<?> generateFieldSchema(Field field, Set<Class<?>> visited, int depth,
                                          boolean excludeAudit, boolean excludeImmutable,
                                          boolean isRequest) {
        Class<?> fieldType = field.getType();
        Type genericType = field.getGenericType();

        if (Collection.class.isAssignableFrom(fieldType)) {
            if (genericType instanceof ParameterizedType paramType) {
                Type[] typeArgs = paramType.getActualTypeArguments();
                if (typeArgs.length > 0 && typeArgs[0] instanceof Class<?> itemClass) {
                    Schema<?> itemSchema = generateSchemaForType(itemClass, visited, depth,
                            excludeAudit, excludeImmutable, isRequest);
                    return new ArraySchema().items(itemSchema);
                }
            }
            return new ArraySchema().items(new Schema<>().type("object"));
        }

        return generateSchemaForType(fieldType, visited, depth, excludeAudit,
                excludeImmutable, isRequest);
    }

    /**
     * Generate schema for a type with filtering.
     */
    private Schema<?> generateSchemaForType(Class<?> type, Set<Class<?>> visited, int depth,
                                            boolean excludeAudit, boolean excludeImmutable,
                                            boolean isRequest) {
        if (type == null || type == Object.class) {
            return new Schema<>().type("object");
        }

        // Primitive and common types
        if (type == String.class) {
            return new Schema<>().type("string");
        } else if (type == Integer.class || type == int.class) {
            return new Schema<>().type("integer").format("int32");
        } else if (type == Long.class || type == long.class) {
            return new Schema<>().type("integer").format("int64");
        } else if (type == Double.class || type == double.class ||
                type == Float.class || type == float.class) {
            return new Schema<>().type("number");
        } else if (type == Boolean.class || type == boolean.class) {
            return new Schema<>().type("boolean");
        } else if (type == java.time.LocalDateTime.class ||
                type == java.time.ZonedDateTime.class ||
                type == java.time.OffsetDateTime.class ||
                type == java.time.Instant.class) {
            return new Schema<>().type("string").format("date-time");
        } else if (type == java.time.LocalDate.class) {
            return new Schema<>().type("string").format("date");
        } else if (type == java.time.LocalTime.class) {
            return new Schema<>().type("string").format("time");
        } else if (type == java.util.Date.class) {
            return new Schema<>().type("string").format("date-time");
        } else if (type == java.math.BigDecimal.class) {
            return new Schema<>().type("number");
        } else if (type.isEnum()) {
            Schema<String> enumSchema = new Schema<>();
            enumSchema.setType("string");
            List<String> enumValues = new ArrayList<>();
            for (Object enumConstant : type.getEnumConstants()) {
                enumValues.add(enumConstant.toString());
            }
            enumSchema.setEnum(enumValues);
            return enumSchema;
        } else if (type.isPrimitive() ||
                (type.getPackage() != null && type.getPackage().getName().startsWith("java."))) {
            return new Schema<>().type("string");
        } else {
            // Complex nested object
            if (visited.contains(type)) {
                Schema<?> refSchema = new Schema<>();
                refSchema.set$ref("#/components/schemas/" + type.getSimpleName());
                return refSchema;
            }

            List<Field> fields = getAllFields(type);
            if (fields.isEmpty() || fields.stream().allMatch(
                    f -> java.lang.reflect.Modifier.isStatic(f.getModifiers()))) {
                return new Schema<>().type("object");
            }

            // 🔧 Don't pass entity class for nested objects - use type itself
            return generateCompleteSchema(type, type, visited, depth,
                    false, false, false); // Nested objects don't filter
        }
    }

    private List<Field> getAllFields(Class<?> clazz) {
        List<Field> fields = new ArrayList<>();
        Class<?> current = clazz;

        while (current != null && current != Object.class) {
            fields.addAll(Arrays.asList(current.getDeclaredFields()));
            current = current.getSuperclass();
        }

        return fields;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Schema<?> createApiResponseSchema(Class<?> dataClass, Class<?> entityClass,
                                              boolean isList) {
        Schema<?> apiResponseSchema = new Schema<>();
        apiResponseSchema.setType("object");

        Schema<?> dataSchema;
        if (isList) {
            dataSchema = new ArraySchema().items(
                    generateCompleteSchema(dataClass, entityClass, new HashSet<>(), 0,
                            false, false, false)); // Response never filters
        } else {
            dataSchema = generateCompleteSchema(dataClass, entityClass, new HashSet<>(), 0,
                    false, false, false); // Response never filters
        }

        Map<String, Schema> properties = new LinkedHashMap<>();
        properties.put("success", new Schema<>().type("boolean").example(true));
        properties.put("message", new Schema<>().type("string").example("Operation successful"));
        properties.put("statusCode", new Schema<>().type("integer").example(200));
        properties.put("status", new Schema<>().type("string").example("OK"));
        properties.put("data", dataSchema);
        properties.put("timestamp", new Schema<>().type("string").format("date-time"));
        properties.put("executionTime", new Schema<>().type("string"));

        apiResponseSchema.setProperties(properties);
        return apiResponseSchema;
    }

    private Class<?> extractEntityClass(HandlerMethod handlerMethod) {
        Type genericSuperclass = handlerMethod.getBeanType().getGenericSuperclass();

        if (genericSuperclass instanceof ParameterizedType paramType) {
            Type[] typeArgs = paramType.getActualTypeArguments();
            if (typeArgs.length > 0 && typeArgs[0] instanceof Class<?> entityClass) {
                return entityClass;
            }
        }

        return null;
    }

    private CrudXOperation detectOperation(String methodName) {
        return switch (methodName) {
            case "create" -> CrudXOperation.CREATE;
            case "createBatch" -> CrudXOperation.BATCH_CREATE;
            case "update" -> CrudXOperation.UPDATE;
            case "updateBatch" -> CrudXOperation.BATCH_UPDATE;
            case "getById" -> CrudXOperation.GET_ID;
            case "getAll" -> CrudXOperation.GET_ALL;
            case "getPaged" -> CrudXOperation.GET_PAGED;
            case "delete" -> CrudXOperation.DELETE;
            case "deleteBatch" -> CrudXOperation.BATCH_DELETE;
            default -> null;
        };
    }

    private boolean isBatchOperation(String methodName) {
        return methodName.contains("Batch") || methodName.contains("batch");
    }

    private boolean isGetAllOperation(String methodName) {
        return "getAll".equals(methodName) || "getPaged".equals(methodName);
    }
}