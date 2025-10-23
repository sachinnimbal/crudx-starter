package io.github.sachinnimbal.crudx.core.config;

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
 * Customizes Swagger/OpenAPI to show complete schemas with nested objects.
 * <p>
 * Fixes:
 * - Resolves all nested class references (Address, Order, CrudXAudit)
 * - Registers component schemas for inner classes
 * - Shows complete entity schema when DTOs are not configured
 * - Handles collections and complex nested structures
 * - Prevents infinite recursion with circular reference detection
 *
 * @author Sachin Nimbal
 * @since 1.0.2
 */
@Slf4j
public class CrudXSwaggerDTOCustomizer implements OperationCustomizer {

    private final CrudXMapperRegistry dtoRegistry;
    private final Map<String, Schema> componentSchemas = new LinkedHashMap<>();
    private static final int MAX_DEPTH = 5; // Maximum nesting depth to prevent stack overflow

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

        log.debug("✓ Using {} schema: {} for {}", usingDto ? "Request DTO" : "Entity", schemaClass.getSimpleName(), crudOperation);

        // Generate complete schema with nested classes
        Schema<?> schema;
        if (isBatchOperation(methodName)) {
            schema = new ArraySchema().items(generateCompleteSchema(schemaClass, new HashSet<>(), 0));
        } else {
            schema = generateCompleteSchema(schemaClass, new HashSet<>(), 0);
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

        log.debug("✓ Using {} schema: {} for {}", usingDto ? "Response DTO" : "Entity", schemaClass.getSimpleName(), crudOperation);

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
                            wrappedSchema = createApiResponseSchema(schemaClass, true);
                        } else {
                            wrappedSchema = createApiResponseSchema(schemaClass, false);
                        }

                        mediaType.setSchema(wrappedSchema);
                    }
                }
            }
        }
    }

    /**
     * Generate complete inline schema without $ref references.
     * This resolves all nested classes inline with circular reference protection.
     *
     * @param clazz The class to generate schema for
     * @param visited Set of classes already being processed in this path
     * @param depth Current recursion depth
     * @return Schema for the class
     */
    private Schema<?> generateCompleteSchema(Class<?> clazz, Set<Class<?>> visited, int depth) {
        // Check for circular reference
        if (visited.contains(clazz)) {
            log.debug("Circular reference detected for {}, using $ref", clazz.getSimpleName());
            Schema<?> refSchema = new Schema<>();
            refSchema.set$ref("#/components/schemas/" + clazz.getSimpleName());
            return refSchema;
        }

        // Check maximum depth
        if (depth > MAX_DEPTH) {
            log.debug("Maximum depth {} reached for {}, using $ref", MAX_DEPTH, clazz.getSimpleName());
            Schema<?> refSchema = new Schema<>();
            refSchema.set$ref("#/components/schemas/" + clazz.getSimpleName());
            return refSchema;
        }

        try {
            // Add to visited set
            Set<Class<?>> newVisited = new HashSet<>(visited);
            newVisited.add(clazz);

            Schema<?> schema = new Schema<>();
            schema.setType("object");
            schema.setName(clazz.getSimpleName());

            Map<String, Schema> properties = new LinkedHashMap<>();

            // Get all fields including inherited ones
            List<Field> allFields = getAllFields(clazz);

            for (Field field : allFields) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    continue;
                }

                String fieldName = field.getName();
                Schema<?> fieldSchema = generateFieldSchema(field, newVisited, depth + 1);
                properties.put(fieldName, fieldSchema);
            }

            schema.setProperties(properties);
            return schema;

        } catch (Exception e) {
            log.warn("Failed to generate complete schema for {}: {}",
                    clazz.getSimpleName(), e.getMessage());
            return new Schema<>().type("object").name(clazz.getSimpleName());
        }
    }

    /**
     * Generate schema for a field, handling all types including nested objects and collections.
     */
    private Schema<?> generateFieldSchema(Field field, Set<Class<?>> visited, int depth) {
        Class<?> fieldType = field.getType();
        Type genericType = field.getGenericType();

        // Handle collections (List, Set, etc.)
        if (Collection.class.isAssignableFrom(fieldType)) {
            if (genericType instanceof ParameterizedType paramType) {
                Type[] typeArgs = paramType.getActualTypeArguments();
                if (typeArgs.length > 0 && typeArgs[0] instanceof Class<?> itemClass) {
                    Schema<?> itemSchema = generateSchemaForType(itemClass, visited, depth);
                    return new ArraySchema().items(itemSchema);
                }
            }
            return new ArraySchema().items(new Schema<>().type("object"));
        }

        // Handle regular types
        return generateSchemaForType(fieldType, visited, depth);
    }

    /**
     * Generate schema for a specific type with circular reference protection.
     */
    private Schema<?> generateSchemaForType(Class<?> type, Set<Class<?>> visited, int depth) {
        // Handle null or Object.class
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
        } else if (type.isPrimitive()) {
            // Handle other primitive types not covered above
            return new Schema<>().type("string");
        } else if (type.getPackage() != null && type.getPackage().getName().startsWith("java.")) {
            // Fallback for other Java built-in types
            return new Schema<>().type("string");
        } else {
            // Complex nested object - check for circular reference before generating
            if (visited.contains(type)) {
                log.debug("Circular reference detected for nested type {}, using $ref", type.getSimpleName());
                Schema<?> refSchema = new Schema<>();
                refSchema.set$ref("#/components/schemas/" + type.getSimpleName());
                return refSchema;
            }

            // Check if it has any declared fields to avoid processing empty classes
            List<Field> fields = getAllFields(type);
            if (fields.isEmpty() || fields.stream().allMatch(f -> java.lang.reflect.Modifier.isStatic(f.getModifiers()))) {
                // Empty object or only static fields
                return new Schema<>().type("object");
            }

            // Generate inline schema with circular reference protection
            return generateCompleteSchema(type, visited, depth);
        }
    }

    /**
     * Get all fields including inherited ones.
     */
    private List<Field> getAllFields(Class<?> clazz) {
        List<Field> fields = new ArrayList<>();
        Class<?> current = clazz;

        while (current != null && current != Object.class) {
            fields.addAll(Arrays.asList(current.getDeclaredFields()));
            current = current.getSuperclass();
        }

        return fields;
    }

    /**
     * Create ApiResponse wrapper schema.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private Schema<?> createApiResponseSchema(Class<?> dataClass, boolean isList) {
        Schema<?> apiResponseSchema = new Schema<>();
        apiResponseSchema.setType("object");

        Schema<?> dataSchema;
        if (isList) {
            dataSchema = new ArraySchema().items(generateCompleteSchema(dataClass, new HashSet<>(), 0));
        } else {
            dataSchema = generateCompleteSchema(dataClass, new HashSet<>(), 0);
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