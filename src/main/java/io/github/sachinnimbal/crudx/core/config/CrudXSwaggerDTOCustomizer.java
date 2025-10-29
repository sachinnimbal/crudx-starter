package io.github.sachinnimbal.crudx.core.config;

import io.github.sachinnimbal.crudx.core.dto.annotations.CrudXRequest;
import io.github.sachinnimbal.crudx.core.dto.mapper.CrudXMapperRegistry;
import io.github.sachinnimbal.crudx.core.enums.CrudXOperation;
import io.github.sachinnimbal.crudx.core.model.CrudXAudit;
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

@Slf4j
public class CrudXSwaggerDTOCustomizer implements OperationCustomizer {

    private final CrudXMapperRegistry dtoRegistry;
    private static final int MAX_DEPTH = 5;

    // For CREATE operations: ONLY createdBy can be provided
    private static final Set<String> CREATE_PROVIDABLE_AUDIT_FIELDS = Set.of("createdBy");

    // For UPDATE operations: ONLY updatedBy can be changed
    private static final Set<String> UPDATE_PROVIDABLE_AUDIT_FIELDS = Set.of("updatedBy");

    // System-managed fields (never user-provided)
    private static final Set<String> SYSTEM_AUDIT_FIELDS = Set.of("createdAt", "updatedAt");

    // All audit fields for reference
    private static final Set<String> ALL_AUDIT_FIELDS = Set.of("createdBy", "updatedBy", "createdAt", "updatedAt");

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

        // CRITICAL: Determine if DTO is actually being used
        boolean isDtoUsed = requestDtoClass.isPresent();

        // Determine filtering rules
        CrudXRequest annotation = isDtoUsed ? schemaClass.getAnnotation(CrudXRequest.class) : null;

        boolean excludeAudit;
        boolean excludeImmutable;
        boolean excludeId;

        if (annotation != null) {
            // DTO with explicit @CrudXRequest configuration
            excludeAudit = annotation.excludeAudit();
            excludeImmutable = annotation.excludeImmutable();
            excludeId = true; // Always exclude ID for requests (it's auto-generated or in path)

            log.debug("Using DTO annotation config for {}: excludeAudit={}, excludeImmutable={}",
                    crudOperation, excludeAudit, excludeImmutable);
        } else if (!isDtoUsed) {
            // NO DTO: Direct entity usage - operation-specific defaults
            if (crudOperation == CrudXOperation.CREATE || crudOperation == CrudXOperation.BATCH_CREATE) {
                // CREATE: Include immutable fields (can be set on creation)
                excludeAudit = false;      // Show audit object BUT filter system fields inside
                excludeImmutable = false;  // Show immutable fields
                excludeId = true;          // Hide ID (auto-generated)
            } else if (crudOperation == CrudXOperation.UPDATE || crudOperation == CrudXOperation.BATCH_UPDATE) {
                // UPDATE: Exclude immutable fields (read-only)
                excludeAudit = false;      // Show audit object BUT filter system fields inside
                excludeImmutable = true;   // Hide immutable fields
                excludeId = true;          // Hide ID (in path param)
            } else {
                excludeAudit = false;
                excludeImmutable = false;
                excludeId = false;
            }

            log.debug("No DTO for {}: excludeAudit={}, excludeImmutable={}",
                    crudOperation, excludeAudit, excludeImmutable);
        } else {
            // DTO exists but no @CrudXRequest annotation: Use CrudX defaults
            excludeAudit = true;       // Hide audit by default in DTOs
            excludeImmutable = true;   // Hide immutable by default in DTOs
            excludeId = true;
        }

        Schema<?> schema;

        // Handle DELETE batch operations - only array of IDs
        if (crudOperation == CrudXOperation.BATCH_DELETE) {
            Schema<?> idSchema = determineIdSchema(entityClass);
            ArraySchema arraySchema = new ArraySchema();
            arraySchema.setItems(idSchema);
            arraySchema.setDescription("Array of entity IDs to delete");
            schema = arraySchema;
        } else if ("updateBatch".equals(methodName)) {
            // Handle updateBatch specially (Map<ID, UpdateData>)
            Schema<?> valueSchema = generateCompleteSchema(schemaClass, entityClass,
                    new HashSet<>(), 0, excludeAudit, excludeImmutable, excludeId, true, crudOperation);

            Schema<?> mapSchema = new Schema<>();
            mapSchema.setType("object");

            Map<String, Schema> properties = new LinkedHashMap<>();
            properties.put("{entityId}", valueSchema);
            mapSchema.setProperties(properties);

            mapSchema.setDescription("Map where key is entity ID and value is update data. Replace {entityId} with actual ID.");

            schema = mapSchema;
        } else if (isBatchOperation(methodName)) {
            // Array of objects for other batch operations (createBatch)
            Schema<?> itemSchema = generateCompleteSchema(schemaClass, entityClass,
                    new HashSet<>(), 0, excludeAudit, excludeImmutable, excludeId, true, crudOperation);

            ArraySchema arraySchema = new ArraySchema();
            arraySchema.setItems(itemSchema);

            schema = arraySchema;
        } else {
            // Single object
            schema = generateCompleteSchema(schemaClass, entityClass,
                    new HashSet<>(), 0, excludeAudit, excludeImmutable, excludeId, true, crudOperation);
        }

        mediaType.setSchema(schema);
    }

    private void updateResponseSchema(Operation operation, Class<?> entityClass,
                                      CrudXOperation crudOperation, String methodName) {
        if (operation.getResponses() == null) return;

        Optional<Class<?>> responseDtoClass = dtoRegistry != null ?
                dtoRegistry.getResponseDTO(entityClass, crudOperation) : Optional.empty();

        Class<?> schemaClass = responseDtoClass.orElse(entityClass);

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

    private Schema<?> generateCompleteSchema(Class<?> schemaClass, Class<?> entityClass,
                                             Set<Class<?>> visited, int depth,
                                             boolean excludeAudit, boolean excludeImmutable,
                                             boolean excludeId, boolean isRequest,
                                             CrudXOperation operation) {
        if (visited.contains(schemaClass)) {
            Schema<?> refSchema = new Schema<>();
            refSchema.set$ref("#/components/schemas/" + schemaClass.getSimpleName());
            return refSchema;
        }

        if (depth > MAX_DEPTH) {
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

                // Apply filtering only for request schemas
                if (isRequest && operation != null) {

                    // RULE 1: Handle 'id' field exclusion
                    if (excludeId && "id".equals(fieldName)) {
                        continue;
                    }

                    // RULE 2: Handle 'audit' object (nested CrudXAudit)
                    if ("audit".equals(fieldName) && isCrudXAuditType(field.getType())) {
                        if (excludeAudit) {
                            // User explicitly wants to exclude audit
                            continue;
                        } else {
                            // Show audit but only with operation-appropriate fields
                            Schema<?> auditSchema = createOperationSpecificAuditSchema(operation);
                            if (auditSchema == null || auditSchema.getProperties() == null ||
                                    auditSchema.getProperties().isEmpty()) {
                                // No user-providable fields for this operation
                                continue;
                            }
                            properties.put(fieldName, auditSchema);
                            continue;
                        }
                    }

                    // RULE 3: Handle individual audit fields (flat structure)
                    if (isAuditFieldName(fieldName)) {
                        if (excludeAudit) {
                            // User explicitly excludes all audit fields
                            continue;
                        } else {
                            // Check if this specific audit field is allowed for this operation
                            if (!isAuditFieldAllowedForOperation(fieldName, operation)) {
                                continue; // System-managed fields always hidden
                            }
                        }
                    }

                    // RULE 4: Handle @CrudXImmutable annotated fields
                    if (excludeImmutable && isFieldImmutable(field, entityClass, fieldName)) {
                        continue;
                    }
                }

                // Generate schema for this field
                Schema<?> fieldSchema = generateFieldSchema(field, newVisited, depth + 1,
                        excludeAudit, excludeImmutable, excludeId, isRequest, operation);

                // Add descriptions for special fields
                if (isRequest) {
                    if (isAuditFieldName(fieldName)) {
                        fieldSchema.setDescription(getAuditFieldDescription(fieldName, operation));
                    }

                    // Add description for immutable fields
                    if (isFieldImmutable(field, entityClass, fieldName)) {
                        String baseDesc = fieldSchema.getDescription();
                        String immutableDesc;

                        if (operation == CrudXOperation.CREATE || operation == CrudXOperation.BATCH_CREATE) {
                            immutableDesc = "Immutable (can be set on creation, cannot be changed later)";
                        } else if (operation == CrudXOperation.UPDATE || operation == CrudXOperation.BATCH_UPDATE) {
                            immutableDesc = "Immutable (read-only, cannot be updated)";
                        } else {
                            immutableDesc = "Immutable field";
                        }

                        fieldSchema.setDescription(baseDesc != null ? baseDesc + " - " + immutableDesc : immutableDesc);
                    }
                }

                properties.put(fieldName, fieldSchema);
            }

            schema.setProperties(properties);
            return schema;

        } catch (Exception e) {
            log.warn("Failed to generate complete schema for {}: {}", schemaClass.getSimpleName(), e.getMessage());
            return new Schema<>().type("object").name(schemaClass.getSimpleName());
        }
    }

    /**
     * Check if a field type is CrudXAudit or extends it
     */
    private boolean isCrudXAuditType(Class<?> fieldType) {
        if (fieldType == null) {
            return false;
        }

        if (fieldType.equals(CrudXAudit.class)) {
            return true;
        }

        Class<?> superClass = fieldType.getSuperclass();
        while (superClass != null && superClass != Object.class) {
            if (superClass.equals(CrudXAudit.class)) {
                return true;
            }
            superClass = superClass.getSuperclass();
        }

        return fieldType.getName().equals("io.github.sachinnimbal.crudx.core.model.CrudXAudit") ||
                fieldType.getSimpleName().equals("CrudXAudit");
    }

    private Schema<?> createOperationSpecificAuditSchema(CrudXOperation operation) {
        Schema<?> auditSchema = new Schema<>();
        auditSchema.setType("object");

        Set<String> allowedFields;
        String description;

        if (operation == CrudXOperation.CREATE || operation == CrudXOperation.BATCH_CREATE) {
            allowedFields = CREATE_PROVIDABLE_AUDIT_FIELDS; // Only "createdBy"
            description = "Audit information (only createdBy can be provided, timestamps are auto-generated)";
        } else if (operation == CrudXOperation.UPDATE || operation == CrudXOperation.BATCH_UPDATE) {
            allowedFields = UPDATE_PROVIDABLE_AUDIT_FIELDS; // Only "updatedBy"
            description = "Audit information (only updatedBy can be provided, timestamps are auto-generated)";
        } else {
            // For other operations, return empty audit schema
            allowedFields = Collections.emptySet();
            description = "Audit information (read-only)";
        }

        auditSchema.setDescription(description);

        Map<String, Schema> auditProps = new LinkedHashMap<>();

        // Only add user-providable fields
        for (String allowedField : allowedFields) {
            Schema<?> fieldSchema = new Schema<>();
            fieldSchema.setType("string");
            fieldSchema.setDescription(getAuditFieldDescription(allowedField, operation));
            auditProps.put(allowedField, fieldSchema);
        }

        auditSchema.setProperties(auditProps);

        // If no properties, return null to exclude the audit field entirely
        if (auditProps.isEmpty()) {
            return null;
        }

        return auditSchema;
    }

    private String getAuditFieldDescription(String fieldName, CrudXOperation operation) {
        return switch (fieldName) {
            case "createdBy" -> {
                if (operation == CrudXOperation.CREATE || operation == CrudXOperation.BATCH_CREATE) {
                    yield "User who created this record (optional, can be provided)";
                }
                yield "User who created this record (read-only)";
            }
            case "updatedBy" -> {
                if (operation == CrudXOperation.UPDATE || operation == CrudXOperation.BATCH_UPDATE) {
                    yield "User who is updating this record (optional, can be provided)";
                }
                yield "User who last updated this record (read-only)";
            }
            case "createdAt" -> "Timestamp when record was created (system-managed, auto-generated)";
            case "updatedAt" -> "Timestamp when record was last updated (system-managed, auto-generated)";
            default -> "Audit field: " + fieldName;
        };
    }

    private boolean isAuditFieldAllowedForOperation(String fieldName, CrudXOperation operation) {
        // System-managed fields NEVER allowed in any request
        if (SYSTEM_AUDIT_FIELDS.contains(fieldName)) {
            return false;
        }

        // User-providable fields: operation-specific
        if (operation == CrudXOperation.CREATE || operation == CrudXOperation.BATCH_CREATE) {
            return CREATE_PROVIDABLE_AUDIT_FIELDS.contains(fieldName); // Only "createdBy"
        } else if (operation == CrudXOperation.UPDATE || operation == CrudXOperation.BATCH_UPDATE) {
            return UPDATE_PROVIDABLE_AUDIT_FIELDS.contains(fieldName); // Only "updatedBy"
        }

        return false;
    }

    /**
     * Checks if a field name is an audit field
     */
    private boolean isAuditFieldName(String fieldName) {
        return ALL_AUDIT_FIELDS.contains(fieldName);
    }

    /**
     * Checks if a field is marked as immutable via @CrudXImmutable annotation
     */
    private boolean isFieldImmutable(Field dtoField, Class<?> entityClass, String fieldName) {
        if (hasImmutableAnnotation(dtoField)) {
            return true;
        }

        Field entityField = findFieldInHierarchy(entityClass, fieldName);
        return entityField != null && hasImmutableAnnotation(entityField);
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

    private Schema<?> generateFieldSchema(Field field, Set<Class<?>> visited, int depth,
                                          boolean excludeAudit, boolean excludeImmutable,
                                          boolean excludeId, boolean isRequest,
                                          CrudXOperation operation) {
        Class<?> fieldType = field.getType();
        Type genericType = field.getGenericType();

        if (isCrudXAuditType(fieldType)) {
            if (isRequest && operation != null) {
                if (excludeAudit) {
                    return new Schema<>().type("object");
                } else if (excludeImmutable) {
                    return createOperationSpecificAuditSchema(operation);
                }
            }
            return generateSchemaForType(fieldType, visited, depth, false, false, false, false, null);
        }

        if (Collection.class.isAssignableFrom(fieldType)) {
            if (genericType instanceof ParameterizedType paramType) {
                Type[] typeArgs = paramType.getActualTypeArguments();
                if (typeArgs.length > 0 && typeArgs[0] instanceof Class<?> itemClass) {
                    Schema<?> itemSchema = generateSchemaForType(itemClass, visited, depth,
                            excludeAudit, excludeImmutable, excludeId, isRequest, operation);
                    return new ArraySchema().items(itemSchema);
                }
            }
            return new ArraySchema().items(new Schema<>().type("object"));
        }

        return generateSchemaForType(fieldType, visited, depth, excludeAudit,
                excludeImmutable, excludeId, isRequest, operation);
    }

    private Schema<?> generateSchemaForType(Class<?> type, Set<Class<?>> visited, int depth,
                                            boolean excludeAudit, boolean excludeImmutable,
                                            boolean excludeId, boolean isRequest,
                                            CrudXOperation operation) {
        if (type == null || type == Object.class) {
            return new Schema<>().type("object");
        }

        if (isCrudXAuditType(type)) {
            if (isRequest && operation != null) {
                if (excludeAudit) {
                    Schema<?> emptySchema = new Schema<>();
                    emptySchema.setType("object");
                    emptySchema.setDescription("Audit information excluded for this operation");
                    return emptySchema;
                } else if (excludeImmutable) {
                    return createOperationSpecificAuditSchema(operation);
                }
            }
            return generateCompleteSchema(type, type, visited, depth, false, false, false, false, null);
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

            boolean nestedExcludeAudit = isRequest && excludeAudit;
            boolean nestedExcludeImmutable = isRequest && excludeImmutable;
            boolean nestedExcludeId = isRequest && excludeId;

            return generateCompleteSchema(type, type, visited, depth,
                    nestedExcludeAudit, nestedExcludeImmutable, nestedExcludeId, isRequest, null);
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

    @SuppressWarnings({"rawtypes"})
    private Schema<?> createApiResponseSchema(Class<?> dataClass, Class<?> entityClass, boolean isList) {
        Schema<?> apiResponseSchema = new Schema<>();
        apiResponseSchema.setType("object");

        Schema<?> dataSchema;
        if (isList) {
            dataSchema = new ArraySchema().items(
                    generateCompleteSchema(dataClass, entityClass, new HashSet<>(), 0,
                            false, false, false, false, null));
        } else {
            dataSchema = generateCompleteSchema(dataClass, entityClass, new HashSet<>(), 0,
                    false, false, false, false, null);
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

    private Schema<?> determineIdSchema(Class<?> entityClass) {
        Field idField = findFieldInHierarchy(entityClass, "id");

        if (idField != null) {
            Class<?> idType = idField.getType();

            if (idType == Long.class || idType == long.class) {
                return new Schema<>().type("integer").format("int64");
            } else if (idType == Integer.class || idType == int.class) {
                return new Schema<>().type("integer").format("int32");
            } else if (idType == String.class) {
                return new Schema<>().type("string");
            } else if (idType == java.util.UUID.class) {
                return new Schema<>().type("string").format("uuid");
            }
        }

        // Default to string if ID type cannot be determined
        return new Schema<>().type("string");
    }
}