package io.github.sachinnimbal.crudx.web;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.sachinnimbal.crudx.core.config.CrudXProperties;
import io.github.sachinnimbal.crudx.core.dto.annotations.CrudXField;
import io.github.sachinnimbal.crudx.core.dto.mapper.CrudXMapper;
import io.github.sachinnimbal.crudx.core.dto.mapper.CrudXMapperGenerator;
import io.github.sachinnimbal.crudx.core.dto.mapper.CrudXMapperRegistry;
import io.github.sachinnimbal.crudx.core.enums.CrudXOperation;
import io.github.sachinnimbal.crudx.core.enums.DatabaseType;
import io.github.sachinnimbal.crudx.core.model.CrudXBaseEntity;
import io.github.sachinnimbal.crudx.core.model.CrudXMongoEntity;
import io.github.sachinnimbal.crudx.core.model.CrudXMySQLEntity;
import io.github.sachinnimbal.crudx.core.model.CrudXPostgreSQLEntity;
import io.github.sachinnimbal.crudx.core.response.ApiResponse;
import io.github.sachinnimbal.crudx.core.response.BatchResult;
import io.github.sachinnimbal.crudx.core.response.PageResponse;
import io.github.sachinnimbal.crudx.service.CrudXService;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static io.github.sachinnimbal.crudx.core.enums.CrudXOperation.*;

@Slf4j
public abstract class CrudXController<T extends CrudXBaseEntity<ID>, ID extends Serializable> {

    @Autowired
    protected ApplicationContext applicationContext;

    @Autowired(required = false)
    protected CrudXMapperRegistry dtoRegistry;

    @Autowired(required = false)
    protected CrudXMapperGenerator mapperGenerator;

    protected CrudXService<T, ID> crudService;

    //  CRITICAL: Strongly typed compiled mapper
    protected CrudXMapper<T, Object, Object> compiledMapper;

    enum MapperMode {
        NONE,           // No DTO mapping
        COMPILED,       // Using compile-time generated mapper (FASTEST)
        RUNTIME         // Using runtime mapper generator (FALLBACK)
    }

    private MapperMode mapperMode = MapperMode.NONE;

    private Class<T> entityClass;
    private Class<ID> idClass;

    @Autowired
    protected CrudXProperties crudxProperties;

    private ObjectMapper objectMapper;

    //  OPTIMIZATION: Cache DTO classes per operation
    private final Map<CrudXOperation, Class<?>> requestDtoCache = new ConcurrentHashMap<>(8);
    private final Map<CrudXOperation, Class<?>> responseDtoCache = new ConcurrentHashMap<>(8);

    //  OPTIMIZATION: Cache field metadata for validation
    private final Map<String, Field> requiredFieldsCache = new ConcurrentHashMap<>();
    private final Map<String, Field> entityFieldsCache = new ConcurrentHashMap<>();

    private static final int MAX_PAGE_SIZE = 100000;
    private static final int LARGE_DATASET_THRESHOLD = 1000;
    private static final int DEFAULT_PAGE_SIZE = 50;


// Updated CrudXController.java - Allow Runtime Fallback with Warnings

    @PostConstruct
    protected void initializeService() {
        resolveGenericTypes();

        if (entityClass == null) {
            throw new IllegalStateException(
                    "Could not resolve entity class for controller: " + getClass().getSimpleName()
            );
        }

        objectMapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
                .disable(MapperFeature.USE_GETTERS_AS_SETTERS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();

        // Initialize service
        DatabaseType databaseType = getDatabaseType();
        String serviceBeanName = Character.toLowerCase(entityClass.getSimpleName().charAt(0)) +
                entityClass.getSimpleName().substring(1) + "Service" + databaseType.name().toLowerCase();

        try {
            @SuppressWarnings("unchecked")
            CrudXService<T, ID> service = (CrudXService<T, ID>)
                    applicationContext.getBean(serviceBeanName, CrudXService.class);
            crudService = service;

            log.info("✓ Controller initialized: {} -> Service: {}",
                    getClass().getSimpleName(), serviceBeanName);
        } catch (Exception e) {
            log.error("Failed to initialize service for controller: {}. Expected service bean: {}",
                    getClass().getSimpleName(), serviceBeanName);
            throw new IllegalStateException(
                    "Service bean not found: " + serviceBeanName, e
            );
        }

        boolean dtoEnabled = crudxProperties.getDto().isEnabled();

        if (!dtoEnabled) {
            mapperMode = MapperMode.NONE;
            log.warn("⚠️  DTO Feature is DISABLED (crudx.dto.enabled=false)");
            log.warn("   - All DTO annotations will be ignored");
            log.warn("   - Using direct entity mapping (zero overhead)");
            return;
        }

        initializeDTOMapping();
        cacheFieldMetadata();
    }

    private void initializeDTOMapping() {
        if (!crudxProperties.getDto().isEnabled()) {
            mapperMode = MapperMode.NONE;
            log.info("❌ DTO feature DISABLED for {} - using entity directly",
                    entityClass.getSimpleName());
            return;
        }

        if (dtoRegistry == null || !dtoRegistry.hasDTOMapping(entityClass)) {
            mapperMode = MapperMode.NONE;
            log.info("ℹ️  No DTO mappings for {} - using entity directly (zero overhead)",
                    entityClass.getSimpleName());
            return;
        }

        String mapperBeanName = Character.toLowerCase(entityClass.getSimpleName().charAt(0)) +
                entityClass.getSimpleName().substring(1) + "MapperCrudX";

        try {
            // ✅ ATTEMPT 1: Get mapper bean
            @SuppressWarnings("unchecked")
            CrudXMapper<T, Object, Object> generatedMapper =
                    (CrudXMapper<T, Object, Object>) applicationContext.getBean(mapperBeanName);

            if (generatedMapper == null) {
                throw new IllegalStateException("Mapper bean found but is null!");
            }

            // 🔥 CRITICAL: Detect if this is actually a RuntimeGeneratedMapper
            String mapperClassName = generatedMapper.getClass().getName();
            boolean isRuntimeMapper = mapperClassName.contains("RuntimeGeneratedMapper");

            if (isRuntimeMapper) {
                // This is the runtime fallback wrapper, not a compiled mapper
                mapperMode = MapperMode.RUNTIME;
                compiledMapper = null; // Don't use it as compiled mapper

                // Clear caches for fresh generation
                clearRuntimeMapperCaches();

                log.warn("════════════════════════════════════════════════════════════════");
                log.warn("⚠️  RUNTIME MAPPER DETECTED FOR {}", entityClass.getSimpleName());
                log.warn("════════════════════════════════════════════════════════════════");
                log.warn("");
                log.warn("📊 PERFORMANCE IMPACT:");
                log.warn("   ✗ Bean type: {}", mapperClassName);
                log.warn("   ✗ Compiled mapper not found in classpath");
                log.warn("   ✗ Using runtime reflection-based mapping");
                log.warn("   ✗ Performance: 10-100x SLOWER than compiled mappers");
                log.warn("   ✗ Memory: Higher allocation, reflection overhead");
                log.warn("");
                log.warn("🔧 TO ENABLE FAST COMPILED MAPPERS:");
                log.warn("");
                log.warn("   GRADLE (build.gradle):");
                log.warn("   ─────────────────────");
                log.warn("   dependencies {{");
                log.warn("       implementation 'io.github.sachinnimbal:crudx-starter:1.2.1'");
                log.warn("       annotationProcessor 'io.github.sachinnimbal:crudx-starter:1.2.1'  // ← ADD THIS");
                log.warn("   }}");
                log.warn("");
                log.warn("   MAVEN (pom.xml):");
                log.warn("   ────────────────");
                log.warn("   <build>");
                log.warn("       <plugins>");
                log.warn("           <plugin>");
                log.warn("               <groupId>org.apache.maven.plugins</groupId>");
                log.warn("               <artifactId>maven-compiler-plugin</artifactId>");
                log.warn("               <configuration>");
                log.warn("                   <annotationProcessorPaths>");
                log.warn("                       <path>");
                log.warn("                           <groupId>io.github.sachinnimbal</groupId>");
                log.warn("                           <artifactId>crudx-starter</artifactId>");
                log.warn("                           <version>1.2.1</version>");
                log.warn("                       </path>");
                log.warn("                   </annotationProcessorPaths>");
                log.warn("               </configuration>");
                log.warn("           </plugin>");
                log.warn("       </plugins>");
                log.warn("   </build>");
                log.warn("");
                log.warn("   THEN RUN:");
                log.warn("   ─────────");
                log.warn("   mvn clean install   (Maven)");
                log.warn("   gradle clean build  (Gradle)");
                log.warn("");
                log.warn("💡 BENEFITS OF COMPILED MAPPERS:");
                log.warn("   ✓ 100x faster mapping performance");
                log.warn("   ✓ Zero reflection overhead");
                log.warn("   ✓ Compile-time validation of DTOs");
                log.warn("   ✓ Lower memory usage");
                log.warn("   ✓ Better IDE support and debugging");
                log.warn("");
                log.warn("════════════════════════════════════════════════════════════════");

                // Pre-cache DTO classes even in runtime mode
                preCacheDTOClasses();

                log.info("   ✓ Pre-cached DTOs: {} request, {} response (runtime mode)",
                        requestDtoCache.size(), responseDtoCache.size());
            } else {
                // This is a real compiled mapper!
                compiledMapper = generatedMapper;
                mapperMode = MapperMode.COMPILED;

                log.info("🚀 COMPILED mapper initialized for {}", entityClass.getSimpleName());
                log.info("   ✓ Bean: {}", mapperBeanName);
                log.info("   ✓ Bean Type: {}", mapperClassName);
                log.info("   ✓ Performance: ZERO runtime overhead, ~100x faster than runtime");
                log.info("   ✓ Memory: Minimal allocation, no reflection");

                // Pre-cache DTO classes for ultra-fast lookup
                preCacheDTOClasses();

                log.info("   ✓ Pre-cached DTOs: {} request, {} response",
                        requestDtoCache.size(), responseDtoCache.size());
            }

        } catch (Exception e) {
            // ✅ FALLBACK: Use runtime mapper generator (with strong warning)
            if (mapperGenerator != null) {
                // 🔥 CLEAR OLD RUNTIME CACHES - Force fresh generation
                clearRuntimeMapperCaches();

                mapperMode = MapperMode.RUNTIME;

                log.warn("════════════════════════════════════════════════════════════════");
                log.warn("⚠️  RUNTIME MAPPER FALLBACK ACTIVE FOR {}", entityClass.getSimpleName());
                log.warn("════════════════════════════════════════════════════════════════");
                log.warn("");
                log.warn("📊 PERFORMANCE IMPACT:");
                log.warn("   ✗ Compiled mapper not found: {}", mapperBeanName);
                log.warn("   ✗ Error: {}", e.getMessage());
                log.warn("   ✗ Using runtime reflection-based mapping");
                log.warn("   ✗ Performance: 10-100x SLOWER than compiled mappers");
                log.warn("   ✗ Memory: Higher allocation, reflection overhead");
                log.warn("   ✗ Startup: Slower due to runtime code generation");
                log.warn("");
                log.warn("🔧 TO ENABLE FAST COMPILED MAPPERS:");
                log.warn("");
                log.warn("   GRADLE (build.gradle):");
                log.warn("   ─────────────────────");
                log.warn("   dependencies {{");
                log.warn("       implementation 'io.github.sachinnimbal:crudx-starter:1.2.1'");
                log.warn("       annotationProcessor 'io.github.sachinnimbal:crudx-starter:1.2.1'  // ← ADD THIS");
                log.warn("   }}");
                log.warn("");
                log.warn("   MAVEN (pom.xml):");
                log.warn("   ────────────────");
                log.warn("   <build>");
                log.warn("       <plugins>");
                log.warn("           <plugin>");
                log.warn("               <groupId>org.apache.maven.plugins</groupId>");
                log.warn("               <artifactId>maven-compiler-plugin</artifactId>");
                log.warn("               <configuration>");
                log.warn("                   <annotationProcessorPaths>");
                log.warn("                       <path>");
                log.warn("                           <groupId>io.github.sachinnimbal</groupId>");
                log.warn("                           <artifactId>crudx-starter</artifactId>");
                log.warn("                           <version>1.2.1</version>");
                log.warn("                       </path>");
                log.warn("                   </annotationProcessorPaths>");
                log.warn("               </configuration>");
                log.warn("           </plugin>");
                log.warn("       </plugins>");
                log.warn("   </build>");
                log.warn("");
                log.warn("   THEN RUN:");
                log.warn("   ─────────");
                log.warn("   mvn clean install   (Maven)");
                log.warn("   gradle clean build  (Gradle)");
                log.warn("");
                log.warn("💡 BENEFITS OF COMPILED MAPPERS:");
                log.warn("   ✓ 100x faster mapping performance");
                log.warn("   ✓ Zero reflection overhead");
                log.warn("   ✓ Compile-time validation of DTOs");
                log.warn("   ✓ Lower memory usage");
                log.warn("   ✓ Better IDE support and debugging");
                log.warn("");
                log.warn("════════════════════════════════════════════════════════════════");

                // Pre-cache DTO classes even in runtime mode
                preCacheDTOClasses();

                log.info("   ✓ Pre-cached DTOs: {} request, {} response (runtime mode)",
                        requestDtoCache.size(), responseDtoCache.size());
            } else {
                mapperMode = MapperMode.NONE;
                log.error("❌ No mapper available for {}", entityClass.getSimpleName());
                log.error("   ✗ Compiled mapper not found: {}", mapperBeanName);
                log.error("   ✗ Runtime generator also unavailable");
                log.error("   → Using direct entity (DTOs ignored)");
            }
        }

        // ✅ FINAL STATUS LOG
        log.info("════════════════════════════════════════════════");
        log.info("Controller: {} | Entity: {} | Mapper: {}",
                getClass().getSimpleName(), entityClass.getSimpleName(), mapperMode);
        if (mapperMode == MapperMode.COMPILED) {
            log.info("✓ COMPILED mapper active: {}", compiledMapper.getClass().getSimpleName());
        } else if (mapperMode == MapperMode.RUNTIME) {
            log.warn("⚠️  RUNTIME mapper active - Add annotationProcessor for 100x speedup");
        } else {
            log.info("ℹ️  No DTOs configured - using direct entity mapping");
        }
        log.info("════════════════════════════════════════════════");
    }

    /**
     * 🔥 NEW: Clear runtime mapper caches to force fresh generation
     * Prevents using stale cached mapping plans from previous runs
     */
    private void clearRuntimeMapperCaches() {
        if (mapperGenerator != null) {
            try {
                mapperGenerator.clearCaches();
                log.info("✓ Cleared runtime mapper caches for fresh generation");
            } catch (Exception e) {
                log.warn("Failed to clear runtime mapper caches: {}", e.getMessage());
            }
        }
    }

    /**
     * OPTIMIZATION: Pre-cache DTO classes to avoid repeated Optional lookups
     */
    private void preCacheDTOClasses() {
        if (dtoRegistry == null) return;

        for (CrudXOperation op : CrudXOperation.values()) {
            dtoRegistry.getRequestDTO(entityClass, op)
                    .ifPresent(dtoClass -> requestDtoCache.put(op, dtoClass));

            dtoRegistry.getResponseDTO(entityClass, op)
                    .ifPresent(dtoClass -> responseDtoCache.put(op, dtoClass));
        }

        log.debug("✓ Pre-cached {} request DTOs, {} response DTOs",
                requestDtoCache.size(), responseDtoCache.size());
    }

    private void cacheFieldMetadata() {
        try {
            for (Field field : getFieldsFast(entityClass)) {
                entityFieldsCache.put(field.getName(), field);

                CrudXField annotation = field.getAnnotation(CrudXField.class);
                if (annotation != null && annotation.required()) {
                    requiredFieldsCache.put(field.getName(), field);
                }
            }

            log.debug("✓ Cached {} entity fields, {} required fields",
                    entityFieldsCache.size(), requiredFieldsCache.size());
        } catch (Exception e) {
            log.warn("Failed to cache field metadata: {}", e.getMessage());
        }
    }

    // ==================== CRUD ENDPOINTS ====================

    @PostMapping
    public ResponseEntity<ApiResponse<?>> create(@Valid @RequestBody Map<String, Object> requestBody) {
        long startTime = System.currentTimeMillis();

        try {
            log.debug("Creating entity: {} (Mapper: {})",
                    entityClass.getSimpleName(), mapperMode);

            if (requestBody == null || requestBody.isEmpty()) {
                throw new IllegalArgumentException("Request body cannot be null or empty");
            }

            T entity = convertMapToEntity(requestBody, CREATE);
            validateRequiredFields(entity);

            beforeCreate(entity);
            T created = crudService.create(entity);
            afterCreate(created);

            long executionTime = System.currentTimeMillis() - startTime;
            Object response = convertEntityToResponse(created, CREATE);

            log.info("Entity created with ID: {} | Time: {} ms | Mapper: {}",
                    created.getId(), executionTime, mapperMode);

            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(ApiResponse.success(response, "Entity created successfully",
                            HttpStatus.CREATED, executionTime));

        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("Error creating entity: {} | Time: {} ms",
                    e.getMessage(), executionTime, e);
            throw new RuntimeException("Failed to create entity: " + e.getMessage(), e);
        }
    }

    @PostMapping("/batch")
    public ResponseEntity<ApiResponse<?>> createBatch(
            @Valid @RequestBody List<Map<String, Object>> requestBodies,
            @RequestParam(required = false, defaultValue = "true") boolean skipDuplicates) {

        long startTime = System.currentTimeMillis();

        // ==================== CRITICAL VALIDATIONS (PREVENT HANGING) ====================

        // 1. NULL/EMPTY CHECK - Immediate fail
        if (requestBodies == null || requestBodies.isEmpty()) {
            throw new IllegalArgumentException("Request body cannot be null or empty");
        }

        // 2. SIZE LIMIT CHECK - Prevent memory overflow
        int totalSize = getSize(requestBodies, crudxProperties.getMaxBatchSize());

        // 4. VALIDATE FIRST RECORD STRUCTURE - Fast fail on bad format
        try {
            if (requestBodies.getFirst() == null || requestBodies.getFirst().isEmpty()) {
                throw new IllegalArgumentException(
                        "First record in batch is null or empty. All records must contain data."
                );
            }
            // Quick test conversion to catch format errors early
            T testEntity = convertMapToEntity(requestBodies.getFirst(), BATCH_CREATE);
            validateRequiredFields(testEntity);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Invalid data format in batch. First record validation failed: " +
                            e.getMessage() + ". Please check your data structure."
            );
        }

        log.info("🚀 Starting batch creation: {} entities (validated)", totalSize);

        // ==================== INTELLIGENT BATCH PROCESSING ====================

        int dbBatchSize = calculateOptimalBatchSize(totalSize);
        int conversionBatchSize = Math.min(200, dbBatchSize / 5);

        int successCount = 0;
        int skipCount = 0;
        List<String> skipReasons = new ArrayList<>();
        int dbHits = 0;

        // Add timeout protection
        long maxProcessingTime = 300000; // 5 minutes max
        long processingDeadline = startTime + maxProcessingTime;

        for (int chunkStart = 0; chunkStart < totalSize; chunkStart += dbBatchSize) {

            // ==================== TIMEOUT CHECK ====================
            if (System.currentTimeMillis() > processingDeadline) {
                String timeoutMsg = String.format(
                        "Batch processing timeout after %d ms. Processed %d/%d records successfully. " +
                                "Increase batch size limits or split into smaller requests.",
                        maxProcessingTime, successCount, totalSize
                );
                log.error(timeoutMsg);

                // Return partial success response with data
                long duration = System.currentTimeMillis() - startTime;
                Map<String, Object> partialResponse = buildBatchResponse(
                        totalSize, successCount, skipCount, dbHits,
                        duration,
                        (successCount * 1000.0) / duration,
                        skipReasons
                );
                partialResponse.put("status", "PARTIAL_SUCCESS");
                partialResponse.put("timeoutError", timeoutMsg);

                // Use success method with data since we have partial results
                return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                        .body(ApiResponse.success(partialResponse,
                                timeoutMsg,
                                HttpStatus.PARTIAL_CONTENT,
                                duration));
            }

            int chunkEnd = Math.min(chunkStart + dbBatchSize, totalSize);
            List<T> chunkEntities = new ArrayList<>(chunkEnd - chunkStart);

            // ==================== CONVERSION PHASE ====================
            for (int i = chunkStart; i < chunkEnd; i += conversionBatchSize) {
                int batchEnd = Math.min(i + conversionBatchSize, chunkEnd);

                for (int j = i; j < batchEnd; j++) {
                    try {
                        Map<String, Object> record = requestBodies.get(j);

                        // Skip null records
                        if (record == null || record.isEmpty()) {
                            skipCount++;
                            if (skipReasons.size() < 1000) {
                                skipReasons.add(String.format("Index %d: Empty or null record", j));
                            }
                            continue;
                        }

                        T entity = convertMapToEntity(record, BATCH_CREATE);
                        validateRequiredFields(entity);
                        chunkEntities.add(entity);

                    } catch (Exception e) {
                        skipCount++;
                        if (skipReasons.size() < 1000) {
                            skipReasons.add(String.format("Index %d: %s", j, e.getMessage()));
                        }
                        log.debug("Skipped record {}: {}", j, e.getMessage());
                    }

                    // Clear processed record to free memory
                    requestBodies.set(j, null);
                }
            }

            // ==================== DATABASE INSERT PHASE ====================
            if (!chunkEntities.isEmpty()) {
                try {
                    beforeCreateBatch(chunkEntities);
                    BatchResult<T> result = crudService.createBatch(chunkEntities, skipDuplicates);
                    afterCreateBatch(result.getCreatedEntities());

                    int inserted = chunkEntities.size() - result.getSkippedCount();
                    successCount += inserted;
                    skipCount += result.getSkippedCount();
                    dbHits++;

                    if (result.getSkippedReasons() != null) {
                        skipReasons.addAll(result.getSkippedReasons());
                    }

                    log.debug("Chunk {}: {} inserted, {} skipped",
                            (chunkStart / dbBatchSize) + 1, inserted, result.getSkippedCount());

                } catch (Exception e) {
                    log.error("❌ Chunk {} failed: {}",
                            (chunkStart / dbBatchSize) + 1, e.getMessage());

                    if (!skipDuplicates) {
                        // Clean up and throw error
                        requestBodies.clear();
                        throw new RuntimeException(
                                String.format("Batch failed at chunk %d: %s",
                                        (chunkStart / dbBatchSize) + 1, e.getMessage()),
                                e
                        );
                    }
                    skipCount += chunkEntities.size();
                }
            }

            chunkEntities.clear();

            // ==================== PROGRESS LOGGING ====================
            if ((chunkStart / dbBatchSize) % 5 == 0 || chunkEnd == totalSize) {
                logRealtimeProgress(totalSize, chunkEnd, successCount, skipCount, startTime);
            }

            // ==================== MEMORY MANAGEMENT ====================
            if ((chunkStart / dbBatchSize) % 50 == 0) {
                System.gc();
            }
        }

        // Clear request list
        requestBodies.clear();

        // ==================== FINAL RESPONSE ====================
        long duration = System.currentTimeMillis() - startTime;
        double recordsPerSecond = duration > 0 ? (successCount * 1000.0) / duration : 0.0;

        Map<String, Object> responseData = buildBatchResponse(
                totalSize, successCount, skipCount, dbHits, duration,
                recordsPerSecond, skipReasons
        );

        log.info("Batch completed: {} created, {} skipped | {} DB hits | {} rec/sec | {} ms",
                successCount, skipCount, dbHits, (int) recordsPerSecond, duration);

        // Determine response status
        HttpStatus status = successCount > 0 ? HttpStatus.CREATED : HttpStatus.BAD_REQUEST;
        String message = successCount > 0
                ? String.format("Batch: %d created, %d skipped", successCount, skipCount)
                : "Batch failed: All records were skipped or invalid";

        return ResponseEntity.status(status)
                .body(ApiResponse.success(responseData, message, status, duration));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<?>> getById(@PathVariable ID id) {
        long startTime = System.currentTimeMillis();
        validateId(id);

        try {
            log.debug("Fetching entity by ID: {} (Mapper: {})", id, mapperMode);

            T entity = crudService.findById(id);
            afterFindById(entity);

            long executionTime = System.currentTimeMillis() - startTime;
            Object response = convertEntityToResponse(entity, GET_ID);

            log.info("Entity found: {} | Time: {} ms | Mapper: {}",
                    id, executionTime, mapperMode);

            return ResponseEntity.ok(ApiResponse.success(response,
                    "Entity retrieved successfully", executionTime));

        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("Error fetching entity {}: {} | Time: {} ms",
                    id, e.getMessage(), executionTime, e);
            throw new RuntimeException("Failed to retrieve entity: " + e.getMessage(), e);
        }
    }

    @GetMapping
    public ResponseEntity<ApiResponse<?>> getAll(
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false, defaultValue = "ASC") String sortDirection) {

        long startTime = System.currentTimeMillis();

        try {
            log.debug("Fetching all entities (Mapper: {})", mapperMode);

            long totalCount = crudService.count();

            if (totalCount > LARGE_DATASET_THRESHOLD) {
                log.warn("Large dataset ({} records) - auto-switching to pagination", totalCount);

                Pageable pageable = createPageable(0, DEFAULT_PAGE_SIZE, sortBy, sortDirection);
                Page<T> springPage = crudService.findAll(pageable);
                PageResponse<T> pageResponse = PageResponse.from(springPage);
                afterFindPaged(pageResponse);

                long executionTime = System.currentTimeMillis() - startTime;
                Object response = convertPageResponseToDTO(pageResponse, GET_PAGED);

                return ResponseEntity.ok(ApiResponse.success(response,
                        String.format("Large dataset (%d records). Returning first %d. Use /paged for more.",
                                totalCount, pageResponse.getContent().size()),
                        executionTime));
            }

            List<T> entities = sortBy != null ?
                    crudService.findAll(Sort.by(Sort.Direction.fromString(sortDirection), sortBy)) :
                    crudService.findAll();

            afterFindAll(entities);

            long executionTime = System.currentTimeMillis() - startTime;
            Object response = convertEntitiesToResponse(entities, GET_ALL);

            log.info("Retrieved {} entities | Time: {} ms | Mapper: {} | DTO enabled: {}",
                    entities.size(), executionTime, mapperMode, mapperMode != MapperMode.NONE);

            return ResponseEntity.ok(ApiResponse.success(response,
                    String.format("Retrieved %d entities", entities.size()),
                    executionTime));

        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("Error fetching all: {} | Time: {} ms",
                    e.getMessage(), executionTime, e);
            throw new RuntimeException("Failed to retrieve entities: " + e.getMessage(), e);
        }
    }

    @GetMapping("/paged")
    public ResponseEntity<ApiResponse<?>> getPaged(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false, defaultValue = "ASC") String sortDirection) {

        long startTime = System.currentTimeMillis();

        try {
            log.debug("Fetching page {} (Mapper: {})", page, mapperMode);

            validatePagination(page, size);

            Pageable pageable = createPageable(page, size, sortBy, sortDirection);
            Page<T> springPage = crudService.findAll(pageable);
            PageResponse<T> pageResponse = PageResponse.from(springPage);
            afterFindPaged(pageResponse);

            long executionTime = System.currentTimeMillis() - startTime;
            Object response = convertPageResponseToDTO(pageResponse, GET_PAGED);

            log.info("Page {} with {} entities (total: {}) | Time: {} ms | Mapper: {} | DTO enabled: {}",
                    page, pageResponse.getContent().size(), pageResponse.getTotalElements(),
                    executionTime, mapperMode, mapperMode != MapperMode.NONE);

            String message = String.format("Retrieved page %d with %d elements (total: %d)",
                    page, pageResponse.getContent().size(), pageResponse.getTotalElements());

            return ResponseEntity.ok(ApiResponse.success(response, message, executionTime));

        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("Error fetching page: {} | Time: {} ms",
                    e.getMessage(), executionTime, e);
            throw new RuntimeException("Failed to retrieve paged data: " + e.getMessage(), e);
        }
    }

    @PatchMapping("/{id}")
    public ResponseEntity<ApiResponse<?>> update(
            @PathVariable ID id,
            @RequestBody @NotEmpty Map<String, Object> updates) {

        long startTime = System.currentTimeMillis();
        validateId(id);

        try {
            log.debug("Updating entity {} (Mapper: {})", id, mapperMode);

            if (updates == null || updates.isEmpty()) {
                throw new IllegalArgumentException("Update data cannot be null or empty");
            }

            T existingEntity = crudService.findById(id);
            beforeUpdate(id, updates, existingEntity);
            T oldEntity = cloneEntity(existingEntity);

            if (mapperMode != MapperMode.NONE) {
                Object requestDto = convertMapToDTO(updates, UPDATE);
                if (requestDto != null) {
                    validateRequiredFields(requestDto);
                }
            }

            T updated = crudService.update(id, updates);
            afterUpdate(updated, oldEntity);

            long executionTime = System.currentTimeMillis() - startTime;
            Object response = convertEntityToResponse(updated, UPDATE);

            log.info("Entity {} updated | Time: {} ms | Mapper: {}",
                    id, executionTime, mapperMode);

            return ResponseEntity.ok(ApiResponse.success(response,
                    "Entity updated successfully", executionTime));

        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("Error updating {}: {} | Time: {} ms",
                    id, e.getMessage(), executionTime, e);
            throw new RuntimeException("Failed to update entity: " + e.getMessage(), e);
        }
    }

    @PatchMapping("/batch")
    public ResponseEntity<ApiResponse<?>> updateBatch(
            @Valid @RequestBody Map<ID, Map<String, Object>> updates) {

        long startTime = System.currentTimeMillis();

        try {
            log.debug("Batch updating {} entities (Mapper: {})",
                    updates.size(), mapperMode);

            if (updates.isEmpty()) {
                throw new IllegalArgumentException("Updates map cannot be empty");
            }

            BatchResult<T> result = crudService.updateBatch(updates);

            long executionTime = System.currentTimeMillis() - startTime;
            Object responseData = convertBatchResultToResponse(result, BATCH_UPDATE);

            String message = result.hasSkipped() ?
                    String.format("Batch update: %d updated, %d skipped",
                            result.getCreatedEntities().size(), result.getSkippedCount()) :
                    String.format("%d entities updated successfully",
                            result.getCreatedEntities().size());

            return ResponseEntity.ok(ApiResponse.success(responseData, message, executionTime));

        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("Batch update error: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to update batch: " + e.getMessage(), e);
        }
    }

    @GetMapping("/count")
    public ResponseEntity<ApiResponse<Long>> count() {
        long startTime = System.currentTimeMillis();

        try {
            long count = crudService.count();
            long executionTime = System.currentTimeMillis() - startTime;

            log.info("Total count: {} | Time: {} ms", count, executionTime);

            return ResponseEntity.ok(ApiResponse.success(count,
                    String.format("Total count: %d", count), executionTime));
        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("Count error: {} | Time: {} ms", e.getMessage(), executionTime, e);
            throw new RuntimeException("Failed to count entities: " + e.getMessage(), e);
        }
    }

    @GetMapping("/exists/{id}")
    public ResponseEntity<ApiResponse<Boolean>> exists(@PathVariable ID id) {
        long startTime = System.currentTimeMillis();
        validateId(id);

        try {
            boolean exists = crudService.existsById(id);
            long executionTime = System.currentTimeMillis() - startTime;

            log.info("Entity {} exists: {} | Time: {} ms", id, exists, executionTime);

            return ResponseEntity.ok(ApiResponse.success(exists,
                    String.format("Entity %s", exists ? "exists" : "does not exist"),
                    executionTime));
        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("Exists check error: {} | Time: {} ms", e.getMessage(), executionTime, e);
            throw new RuntimeException("Failed to check entity existence: " + e.getMessage(), e);
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable ID id) {
        long startTime = System.currentTimeMillis();
        validateId(id);

        try {
            T deletedEntity = crudService.delete(id);
            beforeDelete(id, deletedEntity);
            afterDelete(id, deletedEntity);

            long executionTime = System.currentTimeMillis() - startTime;

            log.info("Entity {} deleted | Time: {} ms", id, executionTime);

            return ResponseEntity.ok(ApiResponse.success(null,
                    "Entity deleted successfully", executionTime));
        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("Delete error {}: {} | Time: {} ms",
                    id, e.getMessage(), executionTime, e);
            throw new RuntimeException("Failed to delete entity: " + e.getMessage(), e);
        }
    }

    @DeleteMapping("/batch")
    public ResponseEntity<ApiResponse<BatchResult<ID>>> deleteBatch(@Valid @RequestBody List<ID> ids) {
        long startTime = System.currentTimeMillis();

        try {
            log.debug("Batch deleting {} IDs", ids.size());

            if (ids.isEmpty()) {
                throw new IllegalArgumentException("ID list cannot be null or empty");
            }

            beforeDeleteBatch(ids);
            BatchResult<T> deletionResult = crudService.deleteBatch(ids);

            List<ID> deletedIds = deletionResult.getCreatedEntities().stream()
                    .map(T::getId)
                    .collect(Collectors.toList());

            afterDeleteBatch(deletedIds);

            BatchResult<ID> result = new BatchResult<>();
            result.setCreatedEntities(deletedIds);
            result.setSkippedCount(deletionResult.getSkippedCount());
            result.setSkippedReasons(deletionResult.getSkippedReasons());

            long executionTime = System.currentTimeMillis() - startTime;

            String message = result.hasSkipped()
                    ? String.format("Batch deletion: %d deleted, %d skipped",
                    result.getCreatedEntities().size(), result.getSkippedCount())
                    : String.format("Batch deletion: %d entities deleted",
                    result.getCreatedEntities().size());

            log.info("{} | Time: {} ms", message, executionTime);

            return ResponseEntity.ok(ApiResponse.success(result, message, executionTime));

        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("Batch delete error: {} | Time: {} ms", e.getMessage(), executionTime, e);
            throw new RuntimeException("Failed to delete batch: " + e.getMessage(), e);
        }
    }

    @DeleteMapping("/batch/force")
    public ResponseEntity<ApiResponse<Void>> deleteBatchForce(@Valid @RequestBody List<ID> ids) {
        long startTime = System.currentTimeMillis();

        try {
            log.debug("Force deleting {} entities (skip existence check)", ids.size());

            if (ids.isEmpty()) {
                throw new IllegalArgumentException("ID list cannot be null or empty");
            }

            if (ids.size() > LARGE_DATASET_THRESHOLD) {
                throw new IllegalArgumentException(
                        String.format("Cannot force delete more than %d records. Current: %d IDs",
                                LARGE_DATASET_THRESHOLD, ids.size()));
            }

            beforeDeleteBatch(ids);

            int batchSize = crudxProperties.getBatchSize();
            int totalDeleted = 0;
            List<ID> actuallyDeletedIds = new ArrayList<>();

            for (int i = 0; i < ids.size(); i += batchSize) {
                int end = Math.min(i + batchSize, ids.size());
                List<ID> batchIds = new ArrayList<>(ids.subList(i, end));

                crudService.deleteBatch(batchIds);
                totalDeleted += batchIds.size();
                actuallyDeletedIds.addAll(batchIds);
                batchIds.clear();

                log.debug("Force deleted {}/{} entities", totalDeleted, ids.size());
            }

            afterDeleteBatch(actuallyDeletedIds);

            long executionTime = System.currentTimeMillis() - startTime;

            log.info("Force deletion: {} IDs processed | Time: {} ms", totalDeleted, executionTime);

            return ResponseEntity.ok(ApiResponse.success(null,
                    String.format("%d IDs processed for deletion", totalDeleted),
                    executionTime));

        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("Force delete error: {} | Time: {} ms", e.getMessage(), executionTime, e);
            throw new RuntimeException("Failed to force delete batch: " + e.getMessage(), e);
        }
    }

    private int getSize(List<Map<String, Object>> requestBodies, int maxBatchSize) {
        int totalSize = requestBodies.size();

        if (totalSize > maxBatchSize) {
            throw new IllegalArgumentException(
                    String.format("Batch size %d exceeds maximum allowed %d. " +
                                    "Please split your request into smaller batches.",
                            totalSize, maxBatchSize)
            );
        }

        // 3. MINIMUM BATCH CHECK - Must have at least 2 records for batch
        if (totalSize < 2) {
            throw new IllegalArgumentException(
                    String.format("Batch creation requires at least 2 records. " +
                                    "Current size: %d. Use POST / endpoint for single record creation.",
                            totalSize)
            );
        }
        return totalSize;
    }

    // ==================== DTO CONVERSION METHODS ====================

    /**
     * INTELLIGENT: Calculate optimal batch size based on dataset
     */
    private int calculateOptimalBatchSize(int totalSize) {
        if (totalSize <= 1000) return Math.min(500, totalSize);
        if (totalSize <= 10_000) return 1000;
        if (totalSize <= 50_000) return 2000;
        if (totalSize <= 100_000) return 5000;
        return 10_000; // Max batch for very large datasets
    }

    /**
     * REAL-TIME: Progress logging with metrics
     */
    private void logRealtimeProgress(int total, int current, int success, int skipped, long startTime) {
        double progress = (double) current / total * 100;
        long elapsed = System.currentTimeMillis() - startTime;
        long estimated = elapsed > 0 ? (long) ((elapsed / progress) * 100) : 0;
        long remaining = estimated - elapsed;

        long currentMemory = (Runtime.getRuntime().totalMemory() -
                Runtime.getRuntime().freeMemory()) / 1024 / 1024;
        long maxMemory = Runtime.getRuntime().maxMemory() / 1024 / 1024;
        double memoryUsage = (double) currentMemory / maxMemory * 100;

        double speed = elapsed > 0 ? (success * 1000.0) / elapsed : 0;

        log.info("📊 Progress: {}/{} ({:.1f}%) | Success: {} | Skipped: {} | Speed: {} rec/sec | " +
                        "Memory: {} MB / {} MB ({:.1f}%) | Elapsed: {} ms | ETA: {} ms",
                current, total, progress, success, skipped, (int) speed,
                currentMemory, maxMemory, memoryUsage, elapsed, remaining);
    }

    /**
     * Build comprehensive response
     */
    private Map<String, Object> buildBatchResponse(int total, int success, int skipped,
                                                   int dbHits, long duration, double recordsPerSecond, List<String> skipReasons) {

        Map<String, Object> responseData = new LinkedHashMap<>();
        responseData.put("totalProcessed", total);
        responseData.put("successCount", success);
        responseData.put("skipCount", skipped);
        responseData.put("databaseHits", dbHits);
        responseData.put("recordsPerSecond", (int) recordsPerSecond);
        responseData.put("executionTimeMs", duration);
        responseData.put("mapperMode", mapperMode.toString());

        // Memory metrics
        long finalMemory = (Runtime.getRuntime().totalMemory() -
                Runtime.getRuntime().freeMemory()) / 1024 / 1024;
        responseData.put("finalMemoryMB", finalMemory);

        // Performance rating
        String performanceRating;
        if (recordsPerSecond > 5000) performanceRating = "EXCELLENT";
        else if (recordsPerSecond > 2000) performanceRating = "GOOD";
        else if (recordsPerSecond > 1000) performanceRating = "MODERATE";
        else performanceRating = "SLOW";
        responseData.put("performanceRating", performanceRating);

        // Error details (first 10 only)
        if (!skipReasons.isEmpty()) {
            responseData.put("errorSample", skipReasons.subList(0, Math.min(10, skipReasons.size())));
            if (skipReasons.size() > 10) {
                responseData.put("errorNote",
                        String.format("Showing first 10 of %d errors", skipReasons.size()));
            }
        }

        return responseData;
    }

    private void updateDtoTypeInRequest() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes)
                    RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                HttpServletRequest request = attrs.getRequest();
                // Set attributes even for COMPILED mode
                if (mapperMode != MapperMode.NONE) {
                    request.setAttribute("dtoType", mapperMode.name());
                    request.setAttribute("dtoUsed", true);

                    // Initialize counter if not present
                    if (request.getAttribute("dtoConversionCount") == null) {
                        request.setAttribute("dtoConversionCount", 0);
                    }
                } else {
                    // Explicitly set NONE for non-DTO operations
                    request.setAttribute("dtoType", "NONE");
                    request.setAttribute("dtoUsed", false);
                }
            }
        } catch (Exception e) {
            log.trace("Failed to update DTO type: {}", e.getMessage());
        }
    }

    // ==================== DTO CONVERSION METHODS ====================

    /**
     * 1. convertMapToEntity() - Used in POST, PATCH operations
     * Supports both COMPILED and RUNTIME modes
     */
    @SuppressWarnings("unchecked")
    private T convertMapToEntity(Map<String, Object> map, CrudXOperation operation) {
        if (mapperMode == MapperMode.NONE) {
            log.trace("Map→Entity: NONE mode (direct conversion)");
            return convertMapToEntityDirectly(map);
        }

        Class<?> requestDtoClass = requestDtoCache.get(operation);
        if (requestDtoClass == null) {
            log.trace("Map→Entity: No DTO class for operation {}, using direct conversion", operation);
            return convertMapToEntityDirectly(map);
        }

        updateDtoTypeInRequest();

        try {
            Object requestDto = objectMapper.convertValue(map, requestDtoClass);

            long start = System.nanoTime();

            T entity;
            if (mapperMode == MapperMode.COMPILED) {
                // 🔥 DEBUG: Verify compiled mapper is actually being used
                if (compiledMapper == null) {
                    log.error("❌ CRITICAL: mapperMode is COMPILED but compiledMapper is NULL!");
                    throw new IllegalStateException("Compiled mapper is null despite COMPILED mode");
                }
                log.debug("✓ Using COMPILED mapper: {} → {}",
                        requestDto.getClass().getSimpleName(), entityClass.getSimpleName());
                entity = compiledMapper.toEntity(requestDto);
            } else {
                // RUNTIME mode: Generate fresh mapping each time
                log.debug("⚠️  Using RUNTIME mapper: {} → {}",
                        requestDto.getClass().getSimpleName(), entityClass.getSimpleName());
                entity = mapperGenerator.toEntity(requestDto, entityClass);
            }

            trackDtoConversion(start, true);

            return entity;

        } catch (Exception e) {
            log.error("DTO mapping failed for {}: {}, falling back to direct conversion",
                    operation, e.getMessage(), e);
            return convertMapToEntityDirectly(map);
        }
    }

    /**
     * 2. convertEntityToResponse() - Used in GET by ID, POST response
     * Supports both COMPILED and RUNTIME modes
     */
    @SuppressWarnings("unchecked")
    private Object convertEntityToResponse(T entity, CrudXOperation operation) {
        if (entity == null) return null;
        if (mapperMode == MapperMode.NONE) return entity;

        Class<?> responseDtoClass = responseDtoCache.get(operation);
        if (responseDtoClass == null) return entity;

        updateDtoTypeInRequest();

        try {
            io.github.sachinnimbal.crudx.core.dto.annotations.CrudXResponse annotation =
                    responseDtoClass.getAnnotation(io.github.sachinnimbal.crudx.core.dto.annotations.CrudXResponse.class);

            long start = System.nanoTime();

            Object response;

            if (mapperMode == MapperMode.COMPILED) {
                if (annotation != null && (annotation.includeId() || annotation.includeAudit())) {
                    try {
                        response = mapperGenerator != null
                                ? mapperGenerator.toResponseMap(entity, responseDtoClass)
                                : compiledMapper.toResponse(entity);
                    } catch (Exception e) {
                        response = compiledMapper.toResponse(entity);
                    }
                } else {
                    response = compiledMapper.toResponse(entity);
                }
            } else {
                // RUNTIME MODE: Generate fresh mapping
                if (annotation != null && (annotation.includeId() || annotation.includeAudit())) {
                    response = mapperGenerator.toResponseMap(entity, responseDtoClass);
                } else {
                    response = mapperGenerator.toResponse(entity, responseDtoClass);
                }
            }

            trackDtoConversion(start, true);

            return response;

        } catch (Exception e) {
            log.error("Response mapping failed: {}, returning entity", e.getMessage(), e);
            return entity;
        }
    }

    /**
     * 3. convertEntitiesToResponse() - Used in GET all, batch responses
     * Supports both COMPILED and RUNTIME modes
     */
    @SuppressWarnings("unchecked")
    private List<?> convertEntitiesToResponse(List<T> entities, CrudXOperation operation) {
        if (entities == null || entities.isEmpty()) return entities;
        if (mapperMode == MapperMode.NONE) return entities;

        Class<?> responseDtoClass = responseDtoCache.get(operation);
        if (responseDtoClass == null) return entities;

        updateDtoTypeInRequest();

        try {
            io.github.sachinnimbal.crudx.core.dto.annotations.CrudXResponse annotation =
                    responseDtoClass.getAnnotation(io.github.sachinnimbal.crudx.core.dto.annotations.CrudXResponse.class);

            long start = System.nanoTime();

            List<?> responses;

            if (mapperMode == MapperMode.COMPILED) {
                if (annotation != null && (annotation.includeId() || annotation.includeAudit())) {
                    responses = entities.stream()
                            .map(entity -> {
                                try {
                                    return mapperGenerator != null
                                            ? mapperGenerator.toResponseMap(entity, responseDtoClass)
                                            : compiledMapper.toResponse(entity);
                                } catch (Exception e) {
                                    return compiledMapper.toResponse(entity);
                                }
                            })
                            .collect(Collectors.toList());
                } else {
                    responses = compiledMapper.toResponseList(entities);
                }
            } else {
                // RUNTIME MODE: Generate fresh mapping
                if (annotation != null && (annotation.includeId() || annotation.includeAudit())) {
                    responses = mapperGenerator.toResponseMapList(entities, responseDtoClass);
                } else {
                    responses = mapperGenerator.toResponseList(entities, responseDtoClass);
                }
            }

            trackDtoConversion(start, true);

            return responses;

        } catch (Exception e) {
            log.error("Batch response mapping failed: {}", e.getMessage(), e);
            return entities;
        }
    }

    // ==================== PERFORMANCE TRACKING ====================

    private void trackDtoConversion(long startNanos, boolean used) {
        long durationMs = (System.nanoTime() - startNanos) / 1_000_000;

        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes)
                    RequestContextHolder.getRequestAttributes();

            if (attrs != null) {
                HttpServletRequest request = attrs.getRequest();

                Long existingTime = (Long) request.getAttribute("dtoConversionTime");
                long totalTime = (existingTime != null ? existingTime : 0L) + durationMs;

                Integer existingCount = (Integer) request.getAttribute("dtoConversionCount");
                int totalCount = (existingCount != null ? existingCount : 0) + 1;

                request.setAttribute("dtoConversionTime", totalTime);
                request.setAttribute("dtoConversionCount", totalCount);
                request.setAttribute("dtoUsed", used ||
                        (request.getAttribute("dtoUsed") != null &&
                                (Boolean) request.getAttribute("dtoUsed")));

                request.setAttribute("dtoType", mapperMode.name());

                if (log.isTraceEnabled()) {
                    log.trace("✓ DTO conversion #{}: +{} ms = {} ms total [Mapper: {}]",
                            totalCount, durationMs, totalTime, mapperMode);
                }

                // 🔥 NEW: Log warning on slow runtime conversions
                if (mapperMode == MapperMode.RUNTIME && durationMs > 50) {
                    log.warn("⚠️  Slow DTO conversion detected: {} ms (Runtime mode). " +
                            "Enable annotation processor for 100x speedup.", durationMs);
                }
            }
        } catch (Exception e) {
            log.trace("DTO tracking failed: {}", e.getMessage());
        }
    }

    /**
     * 4. convertBatchResultToResponse() - Used in batch operations
     * Supports both COMPILED and RUNTIME modes
     */
    @SuppressWarnings("unchecked")
    private Object convertBatchResultToResponse(BatchResult<T> entityResult, CrudXOperation operation) {
        if (mapperMode == MapperMode.NONE) {
            return entityResult;
        }

        updateDtoTypeInRequest();

        try {
            long start = System.nanoTime();

            // This internally calls convertEntitiesToResponse which handles both modes
            List<?> responseDtos = convertEntitiesToResponse(
                    entityResult.getCreatedEntities(), operation);

            BatchResult<Object> dtoResult = new BatchResult<>();
            dtoResult.setCreatedEntities((List<Object>) responseDtos);
            dtoResult.setSkippedCount(entityResult.getSkippedCount());
            dtoResult.setSkippedReasons(entityResult.getSkippedReasons());

            long elapsed = (System.nanoTime() - start) / 1_000_000;

            if (elapsed > 0) {
                log.trace("✓ BatchResult wrapping: {} ms (Mapper: {})", elapsed, mapperMode);
            }

            // 🔥 Warn on slow runtime batch conversions
            if (mapperMode == MapperMode.RUNTIME && elapsed > 100) {
                log.warn("⚠️  Slow batch conversion: {} ms for {} entities (Runtime mode). " +
                                "Enable annotation processor for major speedup.",
                        elapsed, entityResult.getCreatedEntities().size());
            }

            return dtoResult;

        } catch (Exception e) {
            log.error("BatchResult conversion failed: {}", e.getMessage());
            return entityResult;
        }
    }

    /**
     * 5. convertPageResponseToDTO() - Used in paginated GET
     * Supports both COMPILED and RUNTIME modes
     */
    @SuppressWarnings("unchecked")
    private Object convertPageResponseToDTO(PageResponse<T> entityPage, CrudXOperation operation) {
        if (mapperMode == MapperMode.NONE) {
            return entityPage;
        }

        if (entityPage.getContent() == null || entityPage.getContent().isEmpty()) {
            return entityPage;
        }

        updateDtoTypeInRequest();

        try {
            long start = System.nanoTime();

            // This internally calls convertEntitiesToResponse which handles both modes
            List<?> dtoContent = convertEntitiesToResponse(entityPage.getContent(), operation);

            PageResponse<Object> dtoPage = PageResponse.builder()
                    .content((List<Object>) dtoContent)
                    .currentPage(entityPage.getCurrentPage())
                    .pageSize(entityPage.getPageSize())
                    .totalElements(entityPage.getTotalElements())
                    .totalPages(entityPage.getTotalPages())
                    .first(entityPage.isFirst())
                    .last(entityPage.isLast())
                    .empty(entityPage.isEmpty())
                    .build();

            long elapsed = (System.nanoTime() - start) / 1_000_000;

            if (elapsed > 0) {
                log.trace("✓ PageResponse wrapping: {} items in {} ms (Mapper: {})",
                        entityPage.getContent().size(), elapsed, mapperMode);
            }

            // 🔥 Warn on slow runtime page conversions
            if (mapperMode == MapperMode.RUNTIME && elapsed > 100) {
                log.warn("⚠️  Slow page conversion: {} ms for {} entities (Runtime mode). " +
                                "Enable annotation processor for major speedup.",
                        elapsed, entityPage.getContent().size());
            }

            return dtoPage;

        } catch (Exception e) {
            log.error("PageResponse conversion failed: {}", e.getMessage());
            return entityPage;
        }
    }

    /**
     * 6. convertMapToDTO() - Used for validation in PATCH operations
     * This only converts to DTO class, actual mapping happens in update logic
     */
    @SuppressWarnings("unchecked")
    private Object convertMapToDTO(Map<String, Object> map, CrudXOperation operation) {
        if (mapperMode == MapperMode.NONE) {
            return null;
        }

        Class<?> requestDtoClass = requestDtoCache.get(operation);

        if (requestDtoClass == null) {
            return null;
        }

        updateDtoTypeInRequest();

        try {
            long start = System.nanoTime();

            // Convert map to DTO for validation (works same in both COMPILED and RUNTIME modes)
            Object dto = objectMapper.convertValue(map, requestDtoClass);

            trackDtoConversion(start, true);

            return dto;

        } catch (Exception e) {
            log.debug("Map→DTO conversion failed for validation: {}", e.getMessage());
            return null;
        }
    }

    // ==================== STARTUP BANNER ====================

    /**
     * 🔥 NEW: Display performance summary at startup
     */
    @EventListener(ApplicationReadyEvent.class)
    public void displayPerformanceSummary() {
        if (mapperMode == MapperMode.RUNTIME) {
            log.warn("");
            log.warn("╔════════════════════════════════════════════════════════════╗");
            log.warn("║  ⚠️  PERFORMANCE WARNING: Runtime Mapper Active          ║");
            log.warn("╠════════════════════════════════════════════════════════════╣");
            log.warn("║  Entity: {}", String.format("%-48s", entityClass.getSimpleName()) + "║");
            log.warn("║  Status: SLOW - Using reflection-based mapping            ║");
            log.warn("║                                                            ║");
            log.warn("║  💡 TO FIX: Add annotation processor to build config      ║");
            log.warn("║     Gradle: annotationProcessor 'io.github...:crudx...'   ║");
            log.warn("║     Maven: Add to <annotationProcessorPaths>              ║");
            log.warn("║                                                            ║");
            log.warn("║  Expected speedup: 100x faster after rebuild              ║");
            log.warn("╚════════════════════════════════════════════════════════════╝");
            log.warn("");
        }
    }

    /**
     * FALLBACK: Direct Map → Entity conversion (no DTO)
     */
    private T convertMapToEntityDirectly(Map<String, Object> map) {
        try {
            log.trace("Direct conversion: Map→{}", entityClass.getSimpleName());

            Map<String, Object> processedMap = preprocessEnumFields(map);
            return objectMapper.convertValue(processedMap, entityClass);

        } catch (IllegalArgumentException e) {
            if (e.getMessage() != null && e.getMessage().contains("not one of the values accepted")) {
                String betterMessage = "Invalid enum value in request. " + e.getMessage() +
                        ". Note: Use uppercase format (e.g., MALE, FEMALE).";
                log.error("Enum conversion error: {}", betterMessage);
                throw new IllegalArgumentException(betterMessage, e);
            }

            log.error("Failed to convert map to {}: {}",
                    entityClass.getSimpleName(), e.getMessage());
            throw new IllegalArgumentException(
                    "Invalid request body format for " + entityClass.getSimpleName(), e);
        } catch (Exception e) {
            log.error("Failed to convert map to {}: {}",
                    entityClass.getSimpleName(), e.getMessage());
            throw new IllegalArgumentException(
                    "Invalid request body format for " + entityClass.getSimpleName(), e);
        }
    }

    /**
     * OPTIMIZATION: Pre-process enum fields for case-insensitive matching
     */
    private Map<String, Object> preprocessEnumFields(Map<String, Object> map) {
        Map<String, Object> processedMap = new LinkedHashMap<>(map);

        try {
            for (Field field : entityFieldsCache.values()) {
                if (field.getType().isEnum()) {
                    String fieldName = field.getName();
                    Object value = processedMap.get(fieldName);

                    if (value instanceof String) {
                        Object enumValue = findEnumConstant(field.getType(), (String) value);
                        if (enumValue != null) {
                            processedMap.put(fieldName, ((Enum<?>) enumValue).name());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Enum preprocessing error: {}", e.getMessage());
        }

        return processedMap;
    }

    /**
     * OPTIMIZATION: Case-insensitive enum lookup
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object findEnumConstant(Class<?> enumClass, String value) {
        if (value == null || value.isEmpty()) return null;

        try {
            return Enum.valueOf((Class<Enum>) enumClass, value);
        } catch (IllegalArgumentException e) {
            for (Object enumConstant : enumClass.getEnumConstants()) {
                if (((Enum) enumConstant).name().equalsIgnoreCase(value)) {
                    log.debug("Enum '{}' matched case-insensitively to '{}'",
                            value, ((Enum) enumConstant).name());
                    return enumConstant;
                }
            }

            try {
                return Enum.valueOf((Class<Enum>) enumClass, value.toUpperCase());
            } catch (IllegalArgumentException e2) {
                String validValues = Arrays.stream(enumClass.getEnumConstants())
                        .map(c -> ((Enum) c).name())
                        .collect(Collectors.joining(", "));

                log.warn("Invalid enum '{}' for {}. Valid: {}",
                        value, enumClass.getSimpleName(), validValues);
                return null;
            }
        }
    }

    /**
     * OPTIMIZATION: Fast field metadata retrieval
     */
    private Field[] getFieldsFast(Class<?> clazz) {
        List<Field> fields = new ArrayList<>();
        Class<?> current = clazz;

        while (current != null && current != Object.class) {
            Collections.addAll(fields, current.getDeclaredFields());
            current = current.getSuperclass();
        }

        return fields.toArray(new Field[0]);
    }

    /**
     * OPTIMIZATION: Cached required field validation
     */
    private void validateRequiredFields(Object obj) {
        if (obj == null || requiredFieldsCache.isEmpty()) return;

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

    // ==================== HELPER METHODS ====================

    private void validatePagination(int page, int size) {
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

    private Pageable createPageable(int page, int size, String sortBy, String sortDirection) {
        if (sortBy != null) {
            try {
                Sort.Direction direction = Sort.Direction.fromString(sortDirection);
                return PageRequest.of(page, size, Sort.by(direction, sortBy));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "Invalid sort direction: " + sortDirection + ". Must be ASC or DESC");
            }
        }
        return PageRequest.of(page, size);
    }

    private void validateId(ID id) {
        switch (id) {
            case null -> throw new IllegalArgumentException("ID cannot be null");
            case String s when s.trim().isEmpty() -> throw new IllegalArgumentException("ID cannot be empty");
            case Number number when number.longValue() <= 0 ->
                    throw new IllegalArgumentException("ID must be positive");
            default -> {
            }
        }
    }

    @SuppressWarnings("unchecked")
    private T cloneEntity(T entity) {
        try {
            return (T) org.springframework.beans.BeanUtils.instantiateClass(entityClass);
        } catch (Exception e) {
            log.warn("Entity cloning failed", e);
            return null;
        }
    }

    private DatabaseType getDatabaseType() {
        if (CrudXMongoEntity.class.isAssignableFrom(entityClass)) {
            return DatabaseType.MONGODB;
        } else if (CrudXPostgreSQLEntity.class.isAssignableFrom(entityClass)) {
            return DatabaseType.POSTGRESQL;
        } else if (CrudXMySQLEntity.class.isAssignableFrom(entityClass)) {
            return DatabaseType.MYSQL;
        }
        throw new IllegalStateException("Unknown database type for: " + entityClass.getSimpleName());
    }

    @SuppressWarnings("unchecked")
    private void resolveGenericTypes() {
        try {
            Type genericSuperclass = getClass().getGenericSuperclass();
            if (genericSuperclass instanceof ParameterizedType paramType) {
                Type[] typeArgs = paramType.getActualTypeArguments();
                if (typeArgs.length >= 2) {
                    entityClass = (Class<T>) typeArgs[0];
                    idClass = (Class<ID>) typeArgs[1];
                    log.debug("Resolved types - Entity: {}, ID: {}",
                            entityClass.getSimpleName(), idClass.getSimpleName());
                }
            }
        } catch (Exception e) {
            log.error("Generic type resolution failed", e);
        }
    }

    @Data
    @AllArgsConstructor
    private static class ChunkProcessingResult<T> {
        private List<T> createdEntities;
        private int skippedCount;
        private List<String> skippedReasons;
    }

    // ==================== PUBLIC ACCESSORS ====================

    protected Class<T> getEntityClass() {
        return entityClass;
    }

    protected Class<ID> getIdClass() {
        return idClass;
    }

    protected boolean isDTOEnabled() {
        return mapperMode != MapperMode.NONE;
    }

    protected boolean isUsingCompiledMapper() {
        return mapperMode == MapperMode.COMPILED;
    }

    protected String getMapperMode() {
        return mapperMode.name();
    }

    // ==================== LIFECYCLE HOOKS ====================

    protected void beforeCreate(T entity) {
    }

    protected void afterCreate(T entity) {
    }

    protected void beforeCreateBatch(List<T> entities) {
    }

    protected void afterCreateBatch(List<T> entities) {
    }

    protected void beforeUpdate(ID id, Map<String, Object> updates, T existingEntity) {
    }

    protected void afterUpdate(T updatedEntity, T oldEntity) {
    }

    protected void beforeDelete(ID id, T deletedEntity) {
    }

    protected void afterDelete(ID id, T deletedEntity) {
    }

    protected void beforeDeleteBatch(List<ID> ids) {
    }

    protected void afterDeleteBatch(List<ID> deletedIds) {
    }

    protected void afterFindById(T entity) {
    }

    protected void afterFindAll(List<T> entities) {
    }

    protected void afterFindPaged(PageResponse<T> pageResponse) {
    }
}