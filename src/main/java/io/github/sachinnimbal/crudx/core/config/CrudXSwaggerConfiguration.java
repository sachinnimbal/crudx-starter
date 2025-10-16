package io.github.sachinnimbal.crudx.core.config;

import io.github.sachinnimbal.crudx.core.enums.OperationType;
import io.github.sachinnimbal.crudx.dto.registry.CrudXDtoRegistry;
import io.github.sachinnimbal.crudx.web.CrudXController;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.converter.ResolvedSchema;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.servers.Server;
import lombok.extern.slf4j.Slf4j;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;

@Slf4j
@Configuration
@ConditionalOnClass(name = "org.springdoc.core.configuration.SpringDocConfiguration")
@ConditionalOnProperty(prefix = "crudx.swagger", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CrudXSwaggerConfiguration {

    private final Environment environment;

    @Autowired(required = false)
    private CrudXDtoRegistry crudXDtoRegistry;

    private final Set<Class<?>> processedSchemas = Collections.synchronizedSet(new HashSet<>());
    private final Set<String> excludedPackagePrefixes = new HashSet<>();

    public CrudXSwaggerConfiguration(Environment environment) {
        this.environment = environment;

        // Initialize excluded packages for deep scanning
        excludedPackagePrefixes.add("java.");
        excludedPackagePrefixes.add("javax.");
        excludedPackagePrefixes.add("jdk.");
        excludedPackagePrefixes.add("sun.");
        excludedPackagePrefixes.add("org.springframework.");
        excludedPackagePrefixes.add("com.fasterxml.jackson.");
        excludedPackagePrefixes.add("org.hibernate.");
        excludedPackagePrefixes.add("ch.qos.logback.");
        excludedPackagePrefixes.add("org.slf4j.");

        log.info("✓ CrudX Swagger/OpenAPI enabled");
    }

    @Bean
    public OpenAPI crudxOpenAPI() {
        String version = environment.getProperty("project.version", "1.0.0");
        String serverUrl = getServerUrl();

        return new OpenAPI()
                .info(new Info()
                        .title("CrudX Framework API")
                        .description("CRUDX | The Next-Gen Multi-Database CRUD Framework for Spring Boot.")
                        .version(version)
                        .contact(new Contact()
                                .name("CrudX Framework")
                                .email("sachinnimbal9@gmail.com")
                                .url("https://github.com/sachinnimbal/crudx-starter"))
                        .license(new License()
                                .name("Apache 2.0")
                                .url("https://github.com/sachinnimbal/crudx-starter/blob/main/LICENSE")))
                .servers(List.of(
                        new Server()
                                .url(serverUrl)
                                .description("Current Server")
                ));
    }

    @Bean
    public OperationCustomizer crudxSchemaCustomizer() {
        return (operation, handlerMethod) -> {
            if (!CrudXController.class.isAssignableFrom(handlerMethod.getBeanType())) {
                return operation;
            }

            try {
                Class<?> entityClass = getEntityClassFromController(handlerMethod.getBeanType());
                if (entityClass == null) {
                    return operation;
                }

                String methodName = handlerMethod.getMethod().getName();

                switch (methodName) {
                    case "create":
                        customizeCreateOperation(operation, entityClass);
                        break;
                    case "createBatch":
                        customizeBatchCreateOperation(operation, entityClass);
                        break;
                    case "getById":
                        customizeGetByIdOperation(operation, entityClass);
                        break;
                    case "getAll":
                        customizeGetAllOperation(operation, entityClass);
                        break;
                    case "getPaged":
                        customizeGetPagedOperation(operation, entityClass);
                        break;
                    case "update":
                        customizeUpdateOperation(operation, entityClass);
                        break;
                }

            } catch (Exception e) {
                log.debug("Could not customize Swagger schema: {}", e.getMessage());
            }

            return operation;
        };
    }

    // ===== OPERATION CUSTOMIZERS =====

    private void customizeCreateOperation(Operation operation, Class<?> entityClass) {
        Class<?> requestClass = getRequestDtoOrEntity(entityClass, OperationType.CREATE);
        Class<?> responseClass = getResponseDtoOrEntity(entityClass, OperationType.CREATE);

        log.debug("CREATE - Request: {}, Response: {}",
                requestClass.getSimpleName(), responseClass.getSimpleName());

        // ✅ DEEP resolve request schema (including all nested types)
        ResolvedSchema requestSchema = resolveSchemaWithDeepNesting(requestClass);
        ResolvedSchema responseSchema = resolveSchema(responseClass);

        setRequestBodySchema(operation, requestClass, requestSchema, false);
        setResponseSchema(operation, responseClass, responseSchema, false, "201");
    }

    private void customizeBatchCreateOperation(Operation operation, Class<?> entityClass) {
        Class<?> requestClass = getRequestDtoOrEntity(entityClass, OperationType.BATCH_CREATE);
        Class<?> responseClass = getResponseDtoOrEntity(entityClass, OperationType.BATCH_CREATE);

        log.debug("BATCH_CREATE - Request: List<{}>, Response: BatchResult<{}>",
                requestClass.getSimpleName(), responseClass.getSimpleName());

        // ✅ DEEP resolve request schema
        ResolvedSchema requestSchema = resolveSchemaWithDeepNesting(requestClass);
        ResolvedSchema responseSchema = resolveSchema(responseClass);

        setRequestBodySchemaForBatch(operation, requestClass, requestSchema);
        setBatchResultResponseSchema(operation, responseClass, "201");
    }

    private void customizeGetByIdOperation(Operation operation, Class<?> entityClass) {
        Class<?> responseClass = getResponseDtoOrEntity(entityClass, OperationType.GET_BY_ID);

        log.debug("GET_BY_ID - Response: {}", responseClass.getSimpleName());

        ResolvedSchema responseSchema = resolveSchema(responseClass);
        setResponseSchema(operation, responseClass, responseSchema, false, "200");
    }

    private void customizeGetAllOperation(Operation operation, Class<?> entityClass) {
        Class<?> responseClass = getResponseDtoOrEntity(entityClass, OperationType.GET_ALL);

        log.debug("GET_ALL - Response: List<{}>", responseClass.getSimpleName());

        ResolvedSchema responseSchema = resolveSchema(responseClass);
        setResponseSchema(operation, responseClass, responseSchema, true, "200");
    }

    private void customizeGetPagedOperation(Operation operation, Class<?> entityClass) {
        Class<?> responseClass = getResponseDtoOrEntity(entityClass, OperationType.GET_PAGED);

        log.debug("GET_PAGED - Response: PageResponse<{}>", responseClass.getSimpleName());

        ResolvedSchema responseSchema = resolveSchema(responseClass);
        setPageResponseSchema(operation, responseClass, responseSchema, "200");
    }

    private void customizeUpdateOperation(Operation operation, Class<?> entityClass) {
        Class<?> requestClass = getRequestDtoOrEntity(entityClass, OperationType.UPDATE);
        Class<?> responseClass = getResponseDtoOrEntity(entityClass, OperationType.UPDATE);

        log.debug("UPDATE - Request: {}, Response: {}",
                requestClass.getSimpleName(), responseClass.getSimpleName());

        // ✅ DEEP resolve request schema
        ResolvedSchema requestSchema = resolveSchemaWithDeepNesting(requestClass);
        ResolvedSchema responseSchema = resolveSchema(responseClass);

        setRequestBodySchema(operation, requestClass, requestSchema, false);
        setResponseSchema(operation, responseClass, responseSchema, false, "200");
    }

    // ===== HELPER METHODS =====

    private Class<?> getRequestDtoOrEntity(Class<?> entityClass, OperationType operation) {
        if (crudXDtoRegistry != null) {
            Class<?> requestDto = crudXDtoRegistry.getRequestDtoClass(entityClass, operation);
            if (requestDto != null) {
                return requestDto;
            }
        }
        return entityClass;
    }

    private Class<?> getResponseDtoOrEntity(Class<?> entityClass, OperationType operation) {
        if (crudXDtoRegistry != null) {
            Class<?> responseDto = crudXDtoRegistry.getResponseDtoClass(entityClass, operation);
            if (responseDto != null) {
                return responseDto;
            }
        }
        return entityClass;
    }

    /**
     * ✅ NEW: Deep schema resolution with recursive nested type registration
     * This ensures ALL nested types in request bodies are registered upfront
     */
    private ResolvedSchema resolveSchemaWithDeepNesting(Class<?> schemaClass) {
        if (schemaClass == null || processedSchemas.contains(schemaClass)) {
            return null;
        }

        try {
            // Step 1: Use readAll to register main schema + immediate nested types
            Map<String, Schema> allSchemas = ModelConverters.getInstance().readAll(schemaClass);

            if (!allSchemas.isEmpty()) {
                processedSchemas.add(schemaClass);
                log.debug("✓ Deep-registered schema: {} ({} schemas) [REQUEST BODY]",
                        schemaClass.getSimpleName(), allSchemas.size());

                for (String schemaName : allSchemas.keySet()) {
                    log.debug("  → Schema: {}", schemaName);
                }
            }

            // Step 2: ✅ RECURSIVELY register all field types
            registerAllNestedFieldTypes(schemaClass);

            // Step 3: ✅ RECURSIVELY register all inner classes
            registerAllInnerClassesRecursively(schemaClass);

            // Step 4: Resolve as ResolvedSchema
            ResolvedSchema resolvedSchema = ModelConverters.getInstance()
                    .resolveAsResolvedSchema(new AnnotatedType(schemaClass));

            if (resolvedSchema != null && resolvedSchema.schema != null) {
                log.debug("✓ Resolved schema structure for: {}", schemaClass.getSimpleName());
                return resolvedSchema;
            }

        } catch (Exception e) {
            log.error("Could not deep-resolve schema for {}: {}",
                    schemaClass.getSimpleName(), e.getMessage(), e);
        }

        return null;
    }

    /**
     * ✅ Recursively register all nested field types
     */
    private void registerAllNestedFieldTypes(Class<?> clazz) {
        Set<Class<?>> visited = new HashSet<>();
        registerFieldTypesRecursive(clazz, visited);
    }

    private void registerFieldTypesRecursive(Class<?> clazz, Set<Class<?>> visited) {
        if (clazz == null || !visited.add(clazz)) {
            return;
        }

        for (Field field : getAllFieldsIncludingInherited(clazz)) {
            if (Modifier.isStatic(field.getModifiers()) ||
                    Modifier.isTransient(field.getModifiers()) ||
                    field.isSynthetic()) {
                continue;
            }

            Class<?> fieldType = field.getType();

            // Handle Collections: List<X>, Set<X>
            if (Collection.class.isAssignableFrom(fieldType)) {
                Type genericType = field.getGenericType();
                if (genericType instanceof ParameterizedType paramType) {
                    Type[] typeArgs = paramType.getActualTypeArguments();
                    if (typeArgs.length > 0 && typeArgs[0] instanceof Class<?> elementType) {
                        if (shouldRegisterForRequestBody(elementType)) {
                            log.debug("  → Collection element: {}", elementType.getSimpleName());
                            resolveSchemaWithDeepNesting(elementType);
                            registerFieldTypesRecursive(elementType, visited);
                        }
                    }
                }
            }
            // Handle Maps: Map<K, V>
            else if (Map.class.isAssignableFrom(fieldType)) {
                Type genericType = field.getGenericType();
                if (genericType instanceof ParameterizedType paramType) {
                    Type[] typeArgs = paramType.getActualTypeArguments();
                    for (Type typeArg : typeArgs) {
                        if (typeArg instanceof Class<?> paramType1) {
                            if (shouldRegisterForRequestBody(paramType1)) {
                                log.debug("  → Map type: {}", paramType1.getSimpleName());
                                resolveSchemaWithDeepNesting(paramType1);
                                registerFieldTypesRecursive(paramType1, visited);
                            }
                        }
                    }
                }
            }
            // Handle custom object types (POJOs, inner classes, etc.)
            else if (shouldRegisterForRequestBody(fieldType)) {
                log.debug("  → Nested field: {} (type: {})",
                        field.getName(), fieldType.getSimpleName());
                resolveSchemaWithDeepNesting(fieldType);
                registerFieldTypesRecursive(fieldType, visited);
            }
        }
    }

    /**
     * ✅ Recursively register all inner classes at any depth
     */
    private void registerAllInnerClassesRecursively(Class<?> clazz) {
        try {
            Class<?>[] innerClasses = clazz.getDeclaredClasses();

            for (Class<?> innerClass : innerClasses) {
                if (Modifier.isPrivate(innerClass.getModifiers()) ||
                        innerClass.isSynthetic() ||
                        innerClass.isAnonymousClass()) {
                    continue;
                }

                if (shouldRegisterForRequestBody(innerClass)) {
                    log.debug("  → Inner class: {} in {}",
                            innerClass.getSimpleName(), clazz.getSimpleName());
                    resolveSchemaWithDeepNesting(innerClass);
                }
            }
        } catch (Exception e) {
            log.debug("Error scanning inner classes of {}: {}",
                    clazz.getSimpleName(), e.getMessage());
        }
    }

    /**
     * ✅ Check if class should be registered for request body (package-agnostic)
     */
    private boolean shouldRegisterForRequestBody(Class<?> clazz) {
        if (clazz == null ||
                clazz.isPrimitive() ||
                clazz.isArray() ||
                clazz.isEnum() ||
                processedSchemas.contains(clazz)) {
            return false;
        }

        String packageName = clazz.getPackageName();

        // Exclude framework packages
        for (String excludedPrefix : excludedPackagePrefixes) {
            if (packageName.startsWith(excludedPrefix)) {
                return false;
            }
        }

        // Include CrudX classes
        if (packageName.startsWith("io.github.sachinnimbal.crudx")) {
            return true;
        }

        // Include jakarta.persistence only
        if (packageName.startsWith("jakarta.")) {
            return packageName.startsWith("jakarta.persistence");
        }

        // ✅ Accept ALL user application classes
        return true;
    }

    /**
     * Get all fields including inherited ones
     */
    private List<Field> getAllFieldsIncludingInherited(Class<?> clazz) {
        List<Field> fields = new ArrayList<>();
        Class<?> current = clazz;

        while (current != null && current != Object.class) {
            fields.addAll(Arrays.asList(current.getDeclaredFields()));
            current = current.getSuperclass();
        }

        return fields;
    }

    /**
     * Standard schema resolution (for responses - handled by Swagger after API hit)
     */
    private ResolvedSchema resolveSchema(Class<?> schemaClass) {
        if (schemaClass == null || processedSchemas.contains(schemaClass)) {
            return null;
        }

        try {
            Map<String, Schema> allSchemas = ModelConverters.getInstance().readAll(schemaClass);

            if (!allSchemas.isEmpty()) {
                processedSchemas.add(schemaClass);
                log.debug("✓ Registered schema: {} ({} schemas)",
                        schemaClass.getSimpleName(), allSchemas.size());

                for (String schemaName : allSchemas.keySet()) {
                    log.debug("  → Schema: {}", schemaName);
                }
            }

            ResolvedSchema resolvedSchema = ModelConverters.getInstance()
                    .resolveAsResolvedSchema(new AnnotatedType(schemaClass));

            if (resolvedSchema != null && resolvedSchema.schema != null) {
                log.debug("✓ Resolved schema structure for: {}", schemaClass.getSimpleName());
                return resolvedSchema;
            }

        } catch (Exception e) {
            log.error("Could not resolve schema for {}: {}",
                    schemaClass.getSimpleName(), e.getMessage(), e);
        }

        return null;
    }

    /**
     * Set request body schema with resolved type information
     */
    private void setRequestBodySchema(Operation operation, Class<?> schemaClass,
                                      ResolvedSchema resolvedSchema, boolean isArray) {
        RequestBody requestBody = operation.getRequestBody();
        if (requestBody == null) {
            requestBody = new RequestBody();
            requestBody.setRequired(true);
            operation.setRequestBody(requestBody);
        }

        Content content = requestBody.getContent();
        if (content == null) {
            content = new Content();
            requestBody.setContent(content);
        }

        MediaType mediaType = new MediaType();
        Schema<?> schema;

        if (resolvedSchema != null && resolvedSchema.schema != null) {
            if (isArray) {
                ArraySchema arraySchema = new ArraySchema();
                arraySchema.setItems(resolvedSchema.schema);
                schema = arraySchema;
            } else {
                schema = resolvedSchema.schema;
            }
        } else {
            if (isArray) {
                ArraySchema arraySchema = new ArraySchema();
                Schema<?> itemSchema = new Schema<>();
                itemSchema.set$ref("#/components/schemas/" + schemaClass.getSimpleName());
                arraySchema.setItems(itemSchema);
                schema = arraySchema;
            } else {
                schema = new Schema<>();
                schema.set$ref("#/components/schemas/" + schemaClass.getSimpleName());
            }
        }

        mediaType.setSchema(schema);
        content.addMediaType("application/json", mediaType);

        log.debug("✓ Request schema set: {}{}", schemaClass.getSimpleName(), isArray ? "[]" : "");
    }

    /**
     * Set request body schema for batch operations
     */
    private void setRequestBodySchemaForBatch(Operation operation, Class<?> elementClass,
                                              ResolvedSchema resolvedSchema) {
        RequestBody requestBody = operation.getRequestBody();
        if (requestBody == null) {
            requestBody = new RequestBody();
            requestBody.setRequired(true);
            operation.setRequestBody(requestBody);
        }

        Content content = requestBody.getContent();
        if (content == null) {
            content = new Content();
            requestBody.setContent(content);
        }

        MediaType mediaType = new MediaType();
        ArraySchema arraySchema = new ArraySchema();

        if (resolvedSchema != null && resolvedSchema.schema != null) {
            arraySchema.setItems(resolvedSchema.schema);
        } else {
            Schema<?> itemSchema = new Schema<>();
            itemSchema.set$ref("#/components/schemas/" + elementClass.getSimpleName());
            arraySchema.setItems(itemSchema);
        }

        mediaType.setSchema(arraySchema);
        content.addMediaType("application/json", mediaType);

        log.debug("✓ Batch request schema set: {}[]", elementClass.getSimpleName());
    }

    /**
     * Set response schema with resolved type information
     */
    private void setResponseSchema(Operation operation, Class<?> schemaClass,
                                   ResolvedSchema resolvedSchema, boolean isArray, String statusCode) {
        if (operation.getResponses() == null) {
            return;
        }

        Schema<?> dataSchema;

        if (resolvedSchema != null && resolvedSchema.schema != null) {
            if (isArray) {
                ArraySchema arraySchema = new ArraySchema();
                arraySchema.setItems(resolvedSchema.schema);
                dataSchema = arraySchema;
            } else {
                dataSchema = resolvedSchema.schema;
            }
        } else {
            if (isArray) {
                ArraySchema arraySchema = new ArraySchema();
                Schema<?> itemSchema = new Schema<>();
                itemSchema.set$ref("#/components/schemas/" + schemaClass.getSimpleName());
                arraySchema.setItems(itemSchema);
                dataSchema = arraySchema;
            } else {
                dataSchema = new Schema<>();
                dataSchema.set$ref("#/components/schemas/" + schemaClass.getSimpleName());
            }
        }

        ApiResponse response = operation.getResponses().get(statusCode);
        if (response != null) {
            updateApiResponseSchema(response, dataSchema, schemaClass.getSimpleName());
        }

        log.debug("✓ Response schema set: {}{}", schemaClass.getSimpleName(), isArray ? "[]" : "");
    }

    private void setBatchResultResponseSchema(Operation operation, Class<?> elementClass, String statusCode) {
        if (operation.getResponses() == null) {
            return;
        }

        Schema<?> batchResultSchema = new Schema<>();
        batchResultSchema.setType("object");

        ArraySchema createdEntitiesArray = new ArraySchema();
        Schema<?> itemSchema = new Schema<>();
        itemSchema.set$ref("#/components/schemas/" + elementClass.getSimpleName());
        createdEntitiesArray.setItems(itemSchema);

        batchResultSchema.addProperty("createdEntities", createdEntitiesArray);
        batchResultSchema.addProperty("skippedCount", new Schema<>().type("integer"));
        batchResultSchema.addProperty("skippedReasons", new ArraySchema().items(new Schema<>().type("string")));

        ApiResponse response = operation.getResponses().get(statusCode);
        if (response != null) {
            updateApiResponseSchema(response, batchResultSchema, "BatchResult<" + elementClass.getSimpleName() + ">");
        }

        log.debug("✓ BatchResult response schema set: BatchResult<{}>", elementClass.getSimpleName());
    }

    private void setPageResponseSchema(Operation operation, Class<?> elementClass,
                                       ResolvedSchema resolvedSchema, String statusCode) {
        if (operation.getResponses() == null) {
            return;
        }

        Schema<?> pageResponseSchema = new Schema<>();
        pageResponseSchema.setType("object");

        ArraySchema contentArray = new ArraySchema();
        if (resolvedSchema != null && resolvedSchema.schema != null) {
            contentArray.setItems(resolvedSchema.schema);
        } else {
            Schema<?> itemSchema = new Schema<>();
            itemSchema.set$ref("#/components/schemas/" + elementClass.getSimpleName());
            contentArray.setItems(itemSchema);
        }

        pageResponseSchema.addProperty("content", contentArray);
        pageResponseSchema.addProperty("currentPage", new Schema<>().type("integer"));
        pageResponseSchema.addProperty("pageSize", new Schema<>().type("integer"));
        pageResponseSchema.addProperty("totalElements", new Schema<>().type("integer").format("int64"));
        pageResponseSchema.addProperty("totalPages", new Schema<>().type("integer"));
        pageResponseSchema.addProperty("first", new Schema<>().type("boolean"));
        pageResponseSchema.addProperty("last", new Schema<>().type("boolean"));
        pageResponseSchema.addProperty("empty", new Schema<>().type("boolean"));

        ApiResponse response = operation.getResponses().get(statusCode);
        if (response != null) {
            updateApiResponseSchema(response, pageResponseSchema, "PageResponse<" + elementClass.getSimpleName() + ">");
        }

        log.debug("✓ PageResponse schema set: PageResponse<{}>", elementClass.getSimpleName());
    }

    private void updateApiResponseSchema(ApiResponse apiResponse, Schema<?> dataSchema, String description) {
        if (apiResponse.getContent() == null) {
            apiResponse.setContent(new Content());
        }

        MediaType mediaType = new MediaType();

        Schema<?> wrapperSchema = new Schema<>();
        wrapperSchema.setType("object");
        wrapperSchema.addProperty("success", new Schema<>().type("boolean"));
        wrapperSchema.addProperty("message", new Schema<>().type("string"));
        wrapperSchema.addProperty("data", dataSchema);
        wrapperSchema.addProperty("timestamp", new Schema<>().type("string").format("date-time"));
        wrapperSchema.addProperty("executionTimeMs", new Schema<>().type("integer").format("int64"));

        mediaType.setSchema(wrapperSchema);
        apiResponse.getContent().addMediaType("application/json", mediaType);
        apiResponse.setDescription("Success - Returns " + description);
    }

    private Class<?> getEntityClassFromController(Class<?> controllerClass) {
        try {
            Type genericSuperclass = controllerClass.getGenericSuperclass();
            if (genericSuperclass instanceof ParameterizedType paramType) {
                Type[] typeArgs = paramType.getActualTypeArguments();
                if (typeArgs.length >= 1) {
                    return (Class<?>) typeArgs[0];
                }
            }
        } catch (Exception e) {
            log.debug("Could not extract entity class from controller: {}", e.getMessage());
        }
        return null;
    }

    private String getServerUrl() {
        String port = environment.getProperty("server.port", "8080");
        String contextPath = environment.getProperty("server.servlet.context-path", "");
        String host = environment.getProperty("server.address", "localhost");

        if (contextPath.endsWith("/")) {
            contextPath = contextPath.substring(0, contextPath.length() - 1);
        }

        return String.format("http://%s:%s%s", host, port, contextPath);
    }
}