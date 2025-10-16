package io.github.sachinnimbal.crudx.core.config;

import io.github.sachinnimbal.crudx.core.enums.OperationType;
import io.github.sachinnimbal.crudx.dto.registry.CrudXDtoRegistry;
import io.github.sachinnimbal.crudx.web.CrudXController;
import io.swagger.v3.core.converter.ModelConverters;
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
import jakarta.annotation.PostConstruct;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;
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

    private final Set<Class<?>> registeredClasses = Collections.synchronizedSet(new HashSet<>());
    private final Set<String> excludedPackagePrefixes = new HashSet<>();

    public CrudXSwaggerConfiguration(Environment environment) {
        this.environment = environment;
        initializeExcludedPackages();
        log.info("✓ CrudX Swagger/OpenAPI enabled");
    }

    private void initializeExcludedPackages() {
        excludedPackagePrefixes.add("java.");
        excludedPackagePrefixes.add("javax.");
        excludedPackagePrefixes.add("jdk.");
        excludedPackagePrefixes.add("sun.");
        excludedPackagePrefixes.add("org.springframework.");
        excludedPackagePrefixes.add("com.fasterxml.jackson.");
        excludedPackagePrefixes.add("org.hibernate.");
        excludedPackagePrefixes.add("org.apache.catalina.");
        excludedPackagePrefixes.add("org.apache.tomcat.");
        excludedPackagePrefixes.add("ch.qos.logback.");
        excludedPackagePrefixes.add("org.slf4j.");
    }

    @PostConstruct
    public void registerAllModels() {
        try {
            int total = 0;
            log.info("🔍 Pre-registering all models with Swagger...");

            // Step 1: Register CrudX framework base classes FIRST
            total += registerCrudXBaseClasses();

            // Step 2: Register all entities and DTOs
            if (crudXDtoRegistry != null) {
                for (Class<?> entityClass : crudXDtoRegistry.getAllEntities()) {
                    total += registerClassWithDeepScanning(entityClass);
                }

                for (Class<?> dtoClass : crudXDtoRegistry.getAllDtos()) {
                    total += registerClassWithDeepScanning(dtoClass);
                }
            }

            // Step 3: Register response wrappers
            total += registerResponseWrappers();

            log.info("✓ Pre-registered {} schemas with Swagger", total);

        } catch (Exception e) {
            log.warn("Error during model pre-registration: {}", e.getMessage());
        }
    }

    private int registerCrudXBaseClasses() {
        int count = 0;
        try {
            Class<?> auditClass = Class.forName("io.github.sachinnimbal.crudx.core.model.CrudXAudit");
            count += registerClassWithDeepScanning(auditClass);
            log.debug("✓ Pre-registered CrudXAudit");

            try {
                Class<?> baseEntityClass = Class.forName("io.github.sachinnimbal.crudx.core.model.CrudXBaseEntity");
                count += registerClassWithDeepScanning(baseEntityClass);
            } catch (ClassNotFoundException ignored) {
            }

            try {
                Class<?> mongoEntityClass = Class.forName("io.github.sachinnimbal.crudx.core.model.CrudXMongoEntity");
                count += registerClassWithDeepScanning(mongoEntityClass);
            } catch (ClassNotFoundException ignored) {
            }

        } catch (ClassNotFoundException e) {
            log.debug("CrudXAudit not found on classpath");
        }
        return count;
    }

    private int registerResponseWrappers() {
        int count = 0;
        try {
            count += registerClassWithDeepScanning(
                    Class.forName("io.github.sachinnimbal.crudx.core.response.ApiResponse"));
            count += registerClassWithDeepScanning(
                    Class.forName("io.github.sachinnimbal.crudx.core.response.PageResponse"));
            count += registerClassWithDeepScanning(
                    Class.forName("io.github.sachinnimbal.crudx.core.response.BatchResult"));
            log.debug("✓ Registered response wrappers");
        } catch (ClassNotFoundException e) {
            log.debug("Response wrappers not found");
        }
        return count;
    }

    /**
     * ✅ DEEP SCANNING: Register class with complete recursive resolution
     */
    private int registerClassWithDeepScanning(Class<?> clazz) {
        if (!shouldRegister(clazz)) {
            return 0;
        }

        int count = 0;
        try {
            // Use readAll to register ALL schemas including nested ones
            Map<String, Schema> schemas = ModelConverters.getInstance().readAll(clazz);

            if (!schemas.isEmpty()) {
                registeredClasses.add(clazz);
                count++;

                log.debug("✓ Registered: {} ({} schemas)", clazz.getSimpleName(), schemas.size());
                schemas.keySet().forEach(name -> log.debug("  → Schema: {}", name));

                // Register nested types
                count += registerAllFieldTypesRecursively(clazz, new HashSet<>());
                count += registerAllInnerClassesRecursively(clazz);
            }

        } catch (Exception e) {
            log.debug("Could not register {}: {}", clazz.getSimpleName(), e.getMessage());
        }

        return count;
    }

    /**
     * ✅ Register all inner classes recursively (unlimited depth)
     */
    private int registerAllInnerClassesRecursively(Class<?> clazz) {
        int count = 0;
        try {
            for (Class<?> innerClass : clazz.getDeclaredClasses()) {
                if (Modifier.isPrivate(innerClass.getModifiers()) ||
                        innerClass.isSynthetic() ||
                        innerClass.isAnonymousClass()) {
                    continue;
                }

                if (shouldRegister(innerClass)) {
                    log.debug("  → Inner class: {} in {}",
                            innerClass.getSimpleName(), clazz.getSimpleName());
                    count += registerClassWithDeepScanning(innerClass);
                }
            }
        } catch (Exception e) {
            log.debug("Error scanning inner classes: {}", e.getMessage());
        }
        return count;
    }

    /**
     * ✅ Register all field types recursively
     */
    private int registerAllFieldTypesRecursively(Class<?> clazz, Set<Class<?>> visited) {
        if (!visited.add(clazz)) {
            return 0;
        }

        int count = 0;
        for (Field field : getAllFields(clazz)) {
            if (Modifier.isStatic(field.getModifiers()) ||
                    Modifier.isTransient(field.getModifiers()) ||
                    field.isSynthetic()) {
                continue;
            }

            Class<?> fieldType = field.getType();

            // Handle @Embedded/@Embeddable
            if (field.isAnnotationPresent(Embedded.class) ||
                    fieldType.isAnnotationPresent(Embeddable.class)) {
                log.debug("  → @Embedded: {}", fieldType.getSimpleName());
                count += registerClassWithDeepScanning(fieldType);
            }

            // Handle Collections
            if (Collection.class.isAssignableFrom(fieldType)) {
                count += handleGenericType(field.getGenericType(), visited);
            }
            // Handle Maps
            else if (Map.class.isAssignableFrom(fieldType)) {
                count += handleGenericType(field.getGenericType(), visited);
            }
            // Handle custom objects
            else if (shouldRegister(fieldType) && !visited.contains(fieldType)) {
                log.debug("  → Field: {} (type: {})", field.getName(), fieldType.getSimpleName());
                count += registerClassWithDeepScanning(fieldType);
                count += registerAllFieldTypesRecursively(fieldType, visited);
            }
        }
        return count;
    }

    /**
     * ✅ Handle generic types in Collections/Maps
     */
    private int handleGenericType(Type genericType, Set<Class<?>> visited) {
        int count = 0;
        if (genericType instanceof ParameterizedType paramType) {
            for (Type typeArg : paramType.getActualTypeArguments()) {
                if (typeArg instanceof Class<?> typeClass &&
                        shouldRegister(typeClass) &&
                        visited.add(typeClass)) {
                    log.debug("  → Generic type: {}", typeClass.getSimpleName());
                    count += registerClassWithDeepScanning(typeClass);
                    count += registerAllFieldTypesRecursively(typeClass, visited);
                }
            }
        }
        return count;
    }

    /**
     * ✅ Check if class should be registered
     */
    private boolean shouldRegister(Class<?> clazz) {
        if (clazz == null ||
                clazz.isPrimitive() ||
                clazz.isArray() ||
                (clazz.isEnum() && isFrameworkEnum(clazz)) ||
                registeredClasses.contains(clazz)) {
            return false;
        }

        // Always include @Embeddable
        if (clazz.isAnnotationPresent(Embeddable.class)) {
            return true;
        }

        String pkg = clazz.getPackageName();

        // Exclude framework packages
        for (String prefix : excludedPackagePrefixes) {
            if (pkg.startsWith(prefix)) {
                return false;
            }
        }

        // Include CrudX classes
        if (pkg.startsWith("io.github.sachinnimbal.crudx")) {
            return true;
        }

        // Include jakarta.persistence only
        if (pkg.startsWith("jakarta.")) {
            return pkg.startsWith("jakarta.persistence");
        }

        return true;
    }

    private boolean isFrameworkEnum(Class<?> enumClass) {
        String pkg = enumClass.getPackageName();
        return pkg.startsWith("java.") ||
                pkg.startsWith("jakarta.") ||
                pkg.startsWith("org.springframework.");
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

    // ==================== OPENAPI CONFIGURATION ====================

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
                .servers(List.of(new Server().url(serverUrl).description("Current Server")));
    }

    @Bean
    public OperationCustomizer crudxSchemaCustomizer() {
        return (operation, handlerMethod) -> {
            if (!CrudXController.class.isAssignableFrom(handlerMethod.getBeanType())) {
                return operation;
            }

            try {
                Class<?> entityClass = getEntityClass(handlerMethod.getBeanType());
                if (entityClass == null) return operation;

                String method = handlerMethod.getMethod().getName();

                switch (method) {
                    case "create" -> customizeCreate(operation, entityClass);
                    case "createBatch" -> customizeBatchCreate(operation, entityClass);
                    case "getById" -> customizeGetById(operation, entityClass);
                    case "getAll" -> customizeGetAll(operation, entityClass);
                    case "getPaged" -> customizeGetPaged(operation, entityClass);
                    case "update" -> customizeUpdate(operation, entityClass);
                }

            } catch (Exception e) {
                log.debug("Schema customization error: {}", e.getMessage());
            }

            return operation;
        };
    }

    // ==================== OPERATION CUSTOMIZERS ====================

    private void customizeCreate(Operation op, Class<?> entity) {
        Class<?> req = getDto(entity, OperationType.CREATE, true);
        Class<?> res = getDto(entity, OperationType.CREATE, false);
        setRequestBody(op, req, false);
        setResponse(op, res, false, "201");
    }

    private void customizeBatchCreate(Operation op, Class<?> entity) {
        Class<?> req = getDto(entity, OperationType.BATCH_CREATE, true);
        Class<?> res = getDto(entity, OperationType.BATCH_CREATE, false);
        setRequestBody(op, req, true);
        setBatchResponse(op, res, "201");
    }

    private void customizeGetById(Operation op, Class<?> entity) {
        Class<?> res = getDto(entity, OperationType.GET_BY_ID, false);
        setResponse(op, res, false, "200");
    }

    private void customizeGetAll(Operation op, Class<?> entity) {
        Class<?> res = getDto(entity, OperationType.GET_ALL, false);
        setResponse(op, res, true, "200");
    }

    private void customizeGetPaged(Operation op, Class<?> entity) {
        Class<?> res = getDto(entity, OperationType.GET_PAGED, false);
        setPageResponse(op, res, "200");
    }

    private void customizeUpdate(Operation op, Class<?> entity) {
        Class<?> req = getDto(entity, OperationType.UPDATE, true);
        Class<?> res = getDto(entity, OperationType.UPDATE, false);
        setRequestBody(op, req, false);
        setResponse(op, res, false, "200");
    }

    // ==================== HELPER METHODS ====================

    private Class<?> getDto(Class<?> entity, OperationType op, boolean isRequest) {
        if (crudXDtoRegistry != null) {
            Class<?> dto = isRequest ?
                    crudXDtoRegistry.getRequestDtoClass(entity, op) :
                    crudXDtoRegistry.getResponseDtoClass(entity, op);
            if (dto != null) return dto;
        }
        return entity;
    }

    private void setRequestBody(Operation op, Class<?> clazz, boolean isArray) {
        RequestBody rb = op.getRequestBody();
        if (rb == null) {
            rb = new RequestBody().required(true);
            op.setRequestBody(rb);
        }

        Schema<?> schema = createSchemaRef(clazz, isArray);
        MediaType mt = new MediaType().schema(schema);
        rb.setContent(new Content().addMediaType("application/json", mt));
    }

    private void setResponse(Operation op, Class<?> clazz, boolean isArray, String code) {
        if (op.getResponses() == null) return;

        ApiResponse ar = op.getResponses().get(code);
        if (ar != null) {
            Schema<?> dataSchema = createSchemaRef(clazz, isArray);
            Schema<?> wrapper = createApiResponseWrapper(dataSchema);
            MediaType mt = new MediaType().schema(wrapper);
            ar.setContent(new Content().addMediaType("application/json", mt));
        }
    }

    private void setBatchResponse(Operation op, Class<?> clazz, String code) {
        if (op.getResponses() == null) return;

        ApiResponse ar = op.getResponses().get(code);
        if (ar != null) {
            Schema<?> batchSchema = new Schema<>().type("object")
                    .addProperty("createdEntities", new ArraySchema()
                            .items(createSchemaRef(clazz, false)))
                    .addProperty("skippedCount", new Schema<>().type("integer"))
                    .addProperty("skippedReasons", new ArraySchema()
                            .items(new Schema<>().type("string")));

            Schema<?> wrapper = createApiResponseWrapper(batchSchema);
            MediaType mt = new MediaType().schema(wrapper);
            ar.setContent(new Content().addMediaType("application/json", mt));
        }
    }

    private void setPageResponse(Operation op, Class<?> clazz, String code) {
        if (op.getResponses() == null) return;

        ApiResponse ar = op.getResponses().get(code);
        if (ar != null) {
            Schema<?> pageSchema = new Schema<>().type("object")
                    .addProperty("content", new ArraySchema()
                            .items(createSchemaRef(clazz, false)))
                    .addProperty("currentPage", new Schema<>().type("integer"))
                    .addProperty("pageSize", new Schema<>().type("integer"))
                    .addProperty("totalElements", new Schema<>().type("integer").format("int64"))
                    .addProperty("totalPages", new Schema<>().type("integer"))
                    .addProperty("first", new Schema<>().type("boolean"))
                    .addProperty("last", new Schema<>().type("boolean"))
                    .addProperty("empty", new Schema<>().type("boolean"));

            Schema<?> wrapper = createApiResponseWrapper(pageSchema);
            MediaType mt = new MediaType().schema(wrapper);
            ar.setContent(new Content().addMediaType("application/json", mt));
        }
    }

    private Schema<?> createSchemaRef(Class<?> clazz, boolean isArray) {
        Schema<?> ref = new Schema<>().$ref("#/components/schemas/" + clazz.getSimpleName());
        return isArray ? new ArraySchema().items(ref) : ref;
    }

    private Schema<?> createApiResponseWrapper(Schema<?> dataSchema) {
        return new Schema<>().type("object")
                .addProperty("success", new Schema<>().type("boolean"))
                .addProperty("message", new Schema<>().type("string"))
                .addProperty("data", dataSchema)
                .addProperty("timestamp", new Schema<>().type("string").format("date-time"))
                .addProperty("executionTimeMs", new Schema<>().type("integer").format("int64"));
    }

    private Class<?> getEntityClass(Class<?> controller) {
        try {
            Type t = controller.getGenericSuperclass();
            if (t instanceof ParameterizedType pt) {
                return (Class<?>) pt.getActualTypeArguments()[0];
            }
        } catch (Exception e) {
            log.debug("Could not extract entity class: {}", e.getMessage());
        }
        return null;
    }

    private String getServerUrl() {
        String port = environment.getProperty("server.port", "8080");
        String ctx = environment.getProperty("server.servlet.context-path", "");
        String host = environment.getProperty("server.address", "localhost");
        if (ctx.endsWith("/")) ctx = ctx.substring(0, ctx.length() - 1);
        return String.format("http://%s:%s%s", host, port, ctx);
    }
}