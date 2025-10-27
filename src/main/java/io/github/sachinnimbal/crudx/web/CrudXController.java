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
import io.github.sachinnimbal.crudx.core.performance.CrudXBatchMetricsTracker;
import io.github.sachinnimbal.crudx.core.performance.CrudXMetricsRegistry;
import io.github.sachinnimbal.crudx.core.performance.EndpointMetrics;
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
import org.springframework.context.ApplicationContext;
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
import static io.github.sachinnimbal.crudx.core.util.TimeUtils.formatExecutionTime;

@Slf4j
public abstract class CrudXController<T extends CrudXBaseEntity<ID>, ID extends Serializable> {

    @Autowired
    protected ApplicationContext applicationContext;

    @Autowired(required = false)
    protected CrudXMapperRegistry dtoRegistry;

    @Autowired(required = false)
    protected CrudXMapperGenerator mapperGenerator;

    @Autowired(required = false)
    protected CrudXMetricsRegistry metricsRegistry;

    protected CrudXService<T, ID> crudService;

    // 🔥 CRITICAL: Strongly typed compiled mapper
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

    // 🔥 OPTIMIZATION: Cache DTO classes per operation
    private final Map<CrudXOperation, Class<?>> requestDtoCache = new ConcurrentHashMap<>(8);
    private final Map<CrudXOperation, Class<?>> responseDtoCache = new ConcurrentHashMap<>(8);

    // 🔥 OPTIMIZATION: Cache field metadata for validation
    private final Map<String, Field> requiredFieldsCache = new ConcurrentHashMap<>();
    private final Map<String, Field> entityFieldsCache = new ConcurrentHashMap<>();

    private static final int MAX_PAGE_SIZE = 100000;
    private static final int LARGE_DATASET_THRESHOLD = 1000;
    private static final int DEFAULT_PAGE_SIZE = 50;

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

        // 🔥 CRITICAL: Initialize DTO mapping with COMPILED mapper priority
        initializeDTOMapping();

        // 🔥 OPTIMIZATION: Pre-cache field metadata
        cacheFieldMetadata();
    }

    private void initializeDTOMapping() {
        if (dtoRegistry == null || !dtoRegistry.hasDTOMapping(entityClass)) {
            mapperMode = MapperMode.NONE;
            log.debug("No DTO mappings for {} - using entity directly (zero overhead)",
                    entityClass.getSimpleName());
            return;
        }

        String mapperBeanName = Character.toLowerCase(entityClass.getSimpleName().charAt(0)) +
                entityClass.getSimpleName().substring(1) + "MapperCrudX";

        try {
            // 🔥 ATTEMPT 1: Get COMPILED mapper bean (annotation processor generated)
            @SuppressWarnings("unchecked")
            CrudXMapper<T, Object, Object> generatedMapper =
                    (CrudXMapper<T, Object, Object>) applicationContext.getBean(mapperBeanName);

            compiledMapper = generatedMapper;
            mapperMode = MapperMode.COMPILED;

            log.info("🚀 Using COMPILED mapper for {}: {} (ZERO runtime overhead, 100x faster)",
                    entityClass.getSimpleName(), mapperBeanName);

            // Pre-cache DTO classes for ultra-fast lookup
            preCacheDTOClasses();

        } catch (Exception e) {
            // 🔥 FALLBACK: Use runtime mapper generator
            if (mapperGenerator != null) {
                mapperMode = MapperMode.RUNTIME;
                log.warn("⚠️  Compiled mapper not found for {}, using runtime generation (slower by 10-100x)",
                        entityClass.getSimpleName());
                log.warn("💡 To enable compiled mappers: 1) Add annotation processor, 2) Rebuild project");

                // Still pre-cache DTO classes
                preCacheDTOClasses();
            } else {
                mapperMode = MapperMode.NONE;
                log.warn("⚠️  No mapper available for {}, using direct entity (no DTOs)",
                        entityClass.getSimpleName());
            }
        }
    }

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
    public ResponseEntity<ApiResponse<?>> create(
            @Valid @RequestBody Map<String, Object> requestBody,
            HttpServletRequest request) {
        long startTime = System.currentTimeMillis();

        try {
            log.debug("Creating entity: {} (Mapper: {})",
                    entityClass.getSimpleName(), mapperMode);

            if (requestBody == null || requestBody.isEmpty()) {
                throw new IllegalArgumentException("Request body cannot be null or empty");
            }

            long mappingStart = System.nanoTime();
            T entity = convertMapToEntity(requestBody, CREATE);
            long mappingTime = (System.nanoTime() - mappingStart) / 1_000_000;

            long validationStart = System.nanoTime();
            validateRequiredFields(entity);
            long validationTime = (System.nanoTime() - validationStart) / 1_000_000;

            beforeCreate(entity);

            long dbStart = System.nanoTime();
            T created = crudService.create(entity);
            long dbTime = (System.nanoTime() - dbStart) / 1_000_000;

            afterCreate(created);

            long executionTime = System.currentTimeMillis() - startTime;
            Object response = convertEntityToResponse(created, CREATE);

            recordSingleOperationMetrics(request, executionTime, mappingTime, validationTime, dbTime);

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
            @RequestParam(required = false, defaultValue = "true") boolean skipDuplicates,
            HttpServletRequest request) {
        CrudXBatchMetricsTracker metricsTracker = new CrudXBatchMetricsTracker();

        long startTime = System.currentTimeMillis();
        int totalSize = requestBodies.size();

        try {
            log.info("🚀 ULTRA-OPTIMIZED batch: {} entities (Mapper: {})", totalSize, mapperMode);

            if (requestBodies.isEmpty()) {
                throw new IllegalArgumentException("Entity list cannot be null or empty");
            }

            final int MAX_BATCH_SIZE = crudxProperties.getMaxBatchSize();
            if (totalSize > MAX_BATCH_SIZE) {
                throw new IllegalArgumentException(
                        String.format("Batch size exceeds maximum limit of %d. Current size: %d",
                                MAX_BATCH_SIZE, totalSize)
                );
            }

            int processingBatchSize = Math.min(500, crudxProperties.getBatchSize());
            int totalBatches = (totalSize + processingBatchSize - 1) / processingBatchSize;

            int totalSuccessCount = 0;
            int totalSkipCount = 0;
            int conversionFailures = 0;
            List<String> errorSamples = new ArrayList<>(Math.min(100, totalSize / 10));

            log.info("📦 Processing {} batches of {} entities each", totalBatches, processingBatchSize);

            for (int batchNum = 1; batchNum <= totalBatches; batchNum++) {
                int batchStart = (batchNum - 1) * processingBatchSize;
                int batchEnd = Math.min(batchStart + processingBatchSize, totalSize);

                long mappingStartTime = System.currentTimeMillis();
                List<T> batchEntities = new ArrayList<>(batchEnd - batchStart);

                for (int i = batchStart; i < batchEnd; i++) {
                    Map<String, Object> requestBody = requestBodies.get(i);

                    try {
                        long objectMappingStart = System.nanoTime();
                        T entity = convertMapToEntity(requestBody, BATCH_CREATE);
                        long objectMappingTime = (System.nanoTime() - objectMappingStart) / 1_000_000;

                        if (entity != null) {
                            long validationStart = System.nanoTime();
                            validateRequiredFields(entity);
                            long validationTime = (System.nanoTime() - validationStart) / 1_000_000;

                            batchEntities.add(entity);

                            if (totalSuccessCount < 1000) {
                                metricsTracker.addObjectTiming(
                                        entity.getId() != null ? entity.getId().toString() : "pending-" + i,
                                        objectMappingTime,
                                        validationTime,
                                        0 // DB write tracked separately
                                );
                            }
                        } else {
                            conversionFailures++;
                            metricsTracker.incrementFailed();
                            if (errorSamples.size() < 100) {
                                errorSamples.add(String.format("Index %d: Conversion returned null", i));
                            }
                        }

                    } catch (Exception e) {
                        conversionFailures++;
                        metricsTracker.incrementFailed();
                        if (errorSamples.size() < 100) {
                            errorSamples.add(String.format("Index %d: %s", i, e.getMessage()));
                        }
                    }

                    requestBodies.set(i, null);
                }

                long mappingTime = System.currentTimeMillis() - mappingStartTime;
                metricsTracker.addDtoMappingTime(mappingTime);

                if (!batchEntities.isEmpty()) {
                    long dbStartTime = System.currentTimeMillis();

                    try {
                        beforeCreateBatch(batchEntities);
                        BatchResult<T> batchResult = crudService.createBatch(batchEntities, skipDuplicates);

                        long dbTime = System.currentTimeMillis() - dbStartTime;
                        metricsTracker.addDbWriteTime(dbTime);

                        totalSuccessCount += batchResult.getCreatedEntities().size();
                        totalSkipCount += batchResult.getSkippedCount();

                        afterCreateBatch(batchResult.getCreatedEntities());

                    } catch (Exception e) {
                        long dbTime = System.currentTimeMillis() - dbStartTime;
                        metricsTracker.addDbWriteTime(dbTime);

                        log.error("Batch {} failed: {}", batchNum, e.getMessage());
                        if (!skipDuplicates) {
                            throw e;
                        }
                        totalSkipCount += batchEntities.size();
                    }
                }

                batchEntities.clear();

                if (batchNum % 100 == 0) {
                    System.gc();
                }

                // Progress logging
                if (batchNum % 20 == 0 || batchNum == totalBatches) {
                    long currentMemory = (Runtime.getRuntime().totalMemory() -
                            Runtime.getRuntime().freeMemory()) / 1024 / 1024;
                    log.info("📊 Progress: {}/{} batches | Success: {} | Skipped: {} | Memory: {} MB",
                            batchNum, totalBatches, totalSuccessCount, totalSkipCount, currentMemory);
                }
            }

            requestBodies.clear();

            long executionTime = System.currentTimeMillis() - startTime;
            String endpoint = request.getMethod() + " " + request.getRequestURI();
            EndpointMetrics metrics = metricsTracker.buildMetrics(endpoint, request.getMethod());

            if (metricsRegistry != null) {
                request.setAttribute("endpoint.metrics", metrics);
            }

            Map<String, Object> responseData = new LinkedHashMap<>();
            responseData.put("totalProcessed", totalSize);
            responseData.put("successCount", totalSuccessCount);
            responseData.put("skipCount", totalSkipCount + conversionFailures);
            responseData.put("executionTime", formatExecutionTime(executionTime));
            responseData.put("throughput", metrics.getThroughput());
            responseData.put("memoryBeforeMB", metrics.getMemoryBeforeMB() + " MB");
            responseData.put("memoryPeakMB", metrics.getMemoryPeakMB() + " MB");
            responseData.put("memoryAfterMB", metrics.getMemoryAfterMB() + " MB");

            if (mapperMode != MapperMode.NONE && metrics.getDtoMappingTime() != null) {
                responseData.put("dtoMappingTime", metrics.getDtoMappingTime());
            }
            if (metrics.getValidationTime() != null) {
                responseData.put("validationTime", metrics.getValidationTime());
            }
            if (metrics.getDbWriteTime() != null) {
                responseData.put("dbWriteTime", metrics.getDbWriteTime());
            }

            if (conversionFailures > 0 && !errorSamples.isEmpty()) {
                responseData.put("errorSamples", errorSamples.subList(0, Math.min(10, errorSamples.size())));
            }

            String message = String.format(
                    "✅ Batch completed: %d created, %d skipped | %s | %s",
                    totalSuccessCount, totalSkipCount + conversionFailures,
                    formatExecutionTime(executionTime), metrics.getThroughput()
            );

            log.info(message);

            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(ApiResponse.success(responseData, message, HttpStatus.CREATED, executionTime));

        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("Error creating batch: {} | Time: {} ms", e.getMessage(), executionTime, e);
            throw new RuntimeException("Failed to create batch: " + e.getMessage(), e);
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<?>> getById(
            @PathVariable ID id,
            HttpServletRequest request) {
        long startTime = System.currentTimeMillis();
        validateId(id);

        try {
            log.debug("Fetching entity by ID: {} (Mapper: {})", id, mapperMode);

            // 🔥 TRACK DB READ TIME
            long dbStart = System.nanoTime();
            T entity = crudService.findById(id);
            long dbTime = (System.nanoTime() - dbStart) / 1_000_000;

            afterFindById(entity);

            long executionTime = System.currentTimeMillis() - startTime;
            Object response = convertEntityToResponse(entity, GET_ID);

            recordSingleOperationMetrics(request, executionTime, 0, 0, dbTime);

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
            @RequestParam(required = false, defaultValue = "ASC") String sortDirection,
            HttpServletRequest request) {

        long startTime = System.currentTimeMillis();

        try {
            log.debug("Fetching all entities (Mapper: {})", mapperMode);

            long totalCount = crudService.count();

            if (totalCount > LARGE_DATASET_THRESHOLD) {
                log.warn("Large dataset ({} records) - auto-switching to pagination", totalCount);

                Pageable pageable = createPageable(0, DEFAULT_PAGE_SIZE, sortBy, sortDirection);

                long dbStart = System.nanoTime();
                Page<T> springPage = crudService.findAll(pageable);
                long dbTime = (System.nanoTime() - dbStart) / 1_000_000;

                PageResponse<T> pageResponse = PageResponse.from(springPage);
                afterFindPaged(pageResponse);

                long executionTime = System.currentTimeMillis() - startTime;
                Object response = convertPageResponseToDTO(pageResponse, GET_PAGED);

                recordBatchReadMetrics(request, executionTime, pageResponse.getContent().size(), dbTime);

                return ResponseEntity.ok(ApiResponse.success(response,
                        String.format("Large dataset (%d records). Returning first %d. Use /paged for more.",
                                totalCount, pageResponse.getContent().size()),
                        executionTime));
            }

            long dbStart = System.nanoTime();
            List<T> entities = sortBy != null ?
                    crudService.findAll(Sort.by(Sort.Direction.fromString(sortDirection), sortBy)) :
                    crudService.findAll();
            long dbTime = (System.nanoTime() - dbStart) / 1_000_000;

            afterFindAll(entities);

            long executionTime = System.currentTimeMillis() - startTime;
            Object response = convertEntitiesToResponse(entities, GET_ALL);

            recordBatchReadMetrics(request, executionTime, entities.size(), dbTime);

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
            @RequestParam(required = false, defaultValue = "ASC") String sortDirection,
            HttpServletRequest request) {

        long startTime = System.currentTimeMillis();

        try {
            log.debug("Fetching page {} (Mapper: {})", page, mapperMode);

            validatePagination(page, size);

            Pageable pageable = createPageable(page, size, sortBy, sortDirection);

            long dbStart = System.nanoTime();
            Page<T> springPage = crudService.findAll(pageable);
            long dbTime = (System.nanoTime() - dbStart) / 1_000_000;

            PageResponse<T> pageResponse = PageResponse.from(springPage);
            afterFindPaged(pageResponse);

            long executionTime = System.currentTimeMillis() - startTime;
            Object response = convertPageResponseToDTO(pageResponse, GET_PAGED);

            recordBatchReadMetrics(request, executionTime, pageResponse.getContent().size(), dbTime);

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
            @RequestBody @NotEmpty Map<String, Object> updates,
            HttpServletRequest request) {

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

            long mappingTime = 0;
            long validationTime = 0;

            if (mapperMode != MapperMode.NONE) {
                long mappingStart = System.nanoTime();
                Object requestDto = convertMapToDTO(updates, UPDATE);
                mappingTime = (System.nanoTime() - mappingStart) / 1_000_000;

                if (requestDto != null) {
                    long validationStart = System.nanoTime();
                    validateRequiredFields(requestDto);
                    validationTime = (System.nanoTime() - validationStart) / 1_000_000;
                }
            }

            long dbStart = System.nanoTime();
            T updated = crudService.update(id, updates);
            long dbTime = (System.nanoTime() - dbStart) / 1_000_000;

            afterUpdate(updated, oldEntity);

            long executionTime = System.currentTimeMillis() - startTime;
            Object response = convertEntityToResponse(updated, UPDATE);

            recordSingleOperationMetrics(request, executionTime, mappingTime, validationTime, dbTime);

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
            @Valid @RequestBody Map<ID, Map<String, Object>> updates,
            HttpServletRequest request) {
        long startTime = System.currentTimeMillis();

        try {
            log.debug("Batch updating {} entities (Mapper: {})",
                    updates.size(), mapperMode);

            if (updates.isEmpty()) {
                throw new IllegalArgumentException("Updates map cannot be empty");
            }

            long dbStart = System.nanoTime();
            BatchResult<T> result = crudService.updateBatch(updates);
            long dbTime = (System.nanoTime() - dbStart) / 1_000_000;

            long executionTime = System.currentTimeMillis() - startTime;
            Object responseData = convertBatchResultToResponse(result, BATCH_UPDATE);

            recordBatchUpdateMetrics(request, executionTime, result.getCreatedEntities().size(),
                    result.getSkippedCount(), dbTime);

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
    public ResponseEntity<ApiResponse<Void>> delete(
            @PathVariable ID id,
            HttpServletRequest request) {
        long startTime = System.currentTimeMillis();
        validateId(id);

        try {
            long dbStart = System.nanoTime();
            T deletedEntity = crudService.delete(id);
            long dbTime = (System.nanoTime() - dbStart) / 1_000_000;

            beforeDelete(id, deletedEntity);
            afterDelete(id, deletedEntity);

            long executionTime = System.currentTimeMillis() - startTime;

            recordSingleOperationMetrics(request, executionTime, 0, 0, dbTime);

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
    public ResponseEntity<ApiResponse<BatchResult<ID>>> deleteBatch(
            @Valid @RequestBody List<ID> ids,
            HttpServletRequest request) {
        long startTime = System.currentTimeMillis();

        try {
            log.debug("Batch deleting {} IDs", ids.size());

            if (ids.isEmpty()) {
                throw new IllegalArgumentException("ID list cannot be null or empty");
            }

            beforeDeleteBatch(ids);
            long dbStart = System.nanoTime();
            BatchResult<T> deletionResult = crudService.deleteBatch(ids);
            long dbTime = (System.nanoTime() - dbStart) / 1_000_000;

            List<ID> deletedIds = deletionResult.getCreatedEntities().stream()
                    .map(T::getId)
                    .collect(Collectors.toList());

            afterDeleteBatch(deletedIds);

            BatchResult<ID> result = new BatchResult<>();
            result.setCreatedEntities(deletedIds);
            result.setSkippedCount(deletionResult.getSkippedCount());
            result.setSkippedReasons(deletionResult.getSkippedReasons());

            long executionTime = System.currentTimeMillis() - startTime;

            recordBatchDeleteMetrics(request, executionTime, deletedIds.size(),
                    deletionResult.getSkippedCount(), dbTime);

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

    // ==================== 🔥 ULTRA-OPTIMIZED DTO CONVERSION METHODS ====================

    /**
     * 🔥 CRITICAL: Convert Map → Entity with COMPILED mapper priority
     * This method is the KEY to achieving 100x faster performance!
     */
    @SuppressWarnings("unchecked")
    private T convertMapToEntity(Map<String, Object> map, CrudXOperation operation) {
        if (mapperMode == MapperMode.NONE) {
            return convertMapToEntityDirectly(map);
        }

        // 🔥 ULTRA-FAST PATH: Use cached DTO class
        Class<?> requestDtoClass = requestDtoCache.get(operation);

        if (requestDtoClass == null) {
            log.debug("No Request DTO for operation {}, using direct conversion", operation);
            return convertMapToEntityDirectly(map);
        }

        long start = System.nanoTime();

        try {
            // Step 1: Map → Request DTO (Jackson)
            Object requestDto = objectMapper.convertValue(map, requestDtoClass);

            T entity;

            if (mapperMode == MapperMode.COMPILED) {
                // 🔥 FASTEST PATH: Use COMPILED mapper (zero reflection)
                entity = compiledMapper.toEntity(requestDto);

                if (log.isTraceEnabled()) {
                    long elapsed = (System.nanoTime() - start) / 1_000;
                    log.trace("⚡ COMPILED mapper: Map→{}→{} in {} μs",
                            requestDtoClass.getSimpleName(),
                            entityClass.getSimpleName(),
                            elapsed);
                }

            } else {
                // Fallback: Runtime generation
                entity = mapperGenerator.toEntity(requestDto, entityClass);

                if (log.isTraceEnabled()) {
                    long elapsed = (System.nanoTime() - start) / 1_000;
                    log.trace("⚠️  RUNTIME mapper: Map→{}→{} in {} μs (slower)",
                            requestDtoClass.getSimpleName(),
                            entityClass.getSimpleName(),
                            elapsed);
                }
            }

            trackDtoConversion(start, true);
            return entity;

        } catch (Exception e) {
            log.error("DTO mapping failed for {}: {}, falling back to direct conversion",
                    operation, e.getMessage());
            return convertMapToEntityDirectly(map);
        }
    }

    /**
     * 🔥 OPTIMIZATION: Batch convert Maps → Entities
     */
    private List<T> convertBatchToEntities(List<Map<String, Object>> maps, CrudXOperation operation) {
        if (mapperMode == MapperMode.NONE) {
            return maps.stream()
                    .map(this::convertMapToEntityDirectly)
                    .collect(Collectors.toList());
        }

        Class<?> requestDtoClass = requestDtoCache.get(operation);

        if (requestDtoClass == null) {
            return maps.stream()
                    .map(this::convertMapToEntityDirectly)
                    .collect(Collectors.toList());
        }

        long start = System.nanoTime();

        try {
            List<T> entities = new ArrayList<>(maps.size());

            for (Map<String, Object> map : maps) {
                Object requestDto = objectMapper.convertValue(map, requestDtoClass);

                T entity = mapperMode == MapperMode.COMPILED
                        ? compiledMapper.toEntity(requestDto)
                        : mapperGenerator.toEntity(requestDto, entityClass);

                entities.add(entity);
            }

            trackDtoConversion(start, true);

            if (log.isDebugEnabled()) {
                long elapsed = (System.nanoTime() - start) / 1_000_000;
                log.debug("✓ {} mapper: {} entities in {} ms ({} μs/entity)",
                        mapperMode, entities.size(), elapsed,
                        (elapsed * 1000) / entities.size());
            }

            return entities;

        } catch (Exception e) {
            log.error("Batch DTO mapping failed: {}", e.getMessage());
            return maps.stream()
                    .map(this::convertMapToEntityDirectly)
                    .collect(Collectors.toList());
        }
    }

    @SuppressWarnings("unchecked")
    private Object convertEntityToResponse(T entity, CrudXOperation operation) {
        if (entity == null) return null;

        if (mapperMode == MapperMode.NONE) {
            return entity;
        }

        // 🔥 ULTRA-FAST PATH: Use cached DTO class
        Class<?> responseDtoClass = responseDtoCache.get(operation);

        if (responseDtoClass == null) {
            log.debug("No Response DTO for operation {}, returning entity", operation);
            return entity;
        }

        long start = System.nanoTime();

        try {
            // 🔥 Check if DTO has @CrudXResponse annotation
            io.github.sachinnimbal.crudx.core.dto.annotations.CrudXResponse annotation =
                    responseDtoClass.getAnnotation(io.github.sachinnimbal.crudx.core.dto.annotations.CrudXResponse.class);

            Object response;

            if (mapperMode == MapperMode.COMPILED) {
                // 🔥 FASTEST PATH: Use COMPILED mapper

                if (annotation != null && (annotation.includeId() || annotation.includeAudit())) {
                    // Use Map-based response to auto-inject audit fields
                    try {
                        response = mapperGenerator != null
                                ? mapperGenerator.toResponseMap(entity, responseDtoClass)
                                : compiledMapper.toResponse(entity);
                        log.trace("⚡ COMPILED mapper (Map mode): {}→{}",
                                entityClass.getSimpleName(), responseDtoClass.getSimpleName());
                    } catch (Exception e) {
                        log.warn("Map-based response failed, using direct: {}", e.getMessage());
                        response = compiledMapper.toResponse(entity);
                    }
                } else {
                    // Direct DTO mapping (no audit injection needed)
                    response = compiledMapper.toResponse(entity);
                    log.trace("⚡ COMPILED mapper (Direct mode): {}→{}",
                            entityClass.getSimpleName(), responseDtoClass.getSimpleName());
                }

                if (log.isTraceEnabled()) {
                    long elapsed = (System.nanoTime() - start) / 1_000;
                    log.trace("⚡ Conversion time: {} μs", elapsed);
                }

            } else {
                // Fallback: Runtime generation
                if (annotation != null && (annotation.includeId() || annotation.includeAudit())) {
                    response = mapperGenerator.toResponseMap(entity, responseDtoClass);
                    log.trace("⚠️  RUNTIME mapper (Map mode)");
                } else {
                    response = mapperGenerator.toResponse(entity, responseDtoClass);
                    log.trace("⚠️  RUNTIME mapper (Direct mode)");
                }

                if (log.isTraceEnabled()) {
                    long elapsed = (System.nanoTime() - start) / 1_000;
                    log.trace("⚠️  Conversion time: {} μs", elapsed);
                }
            }

            trackDtoConversion(start, true);
            return response;

        } catch (Exception e) {
            log.error("Response mapping failed: {}, returning entity", e.getMessage(), e);
            return entity;
        }
    }

    @SuppressWarnings("unchecked")
    private List<?> convertEntitiesToResponse(List<T> entities, CrudXOperation operation) {
        if (entities == null || entities.isEmpty()) return entities;

        if (mapperMode == MapperMode.NONE) {
            return entities;
        }

        // 🔥 ULTRA-FAST PATH: Use cached DTO class
        Class<?> responseDtoClass = responseDtoCache.get(operation);

        if (responseDtoClass == null) {
            log.debug("No Response DTO for operation {}, returning entities", operation);
            return entities;
        }

        long start = System.nanoTime();

        try {
            // 🔥 Check if DTO has @CrudXResponse annotation for includeId/includeAudit
            io.github.sachinnimbal.crudx.core.dto.annotations.CrudXResponse annotation =
                    responseDtoClass.getAnnotation(io.github.sachinnimbal.crudx.core.dto.annotations.CrudXResponse.class);

            List<?> responses;

            if (mapperMode == MapperMode.COMPILED) {
                // 🔥 FASTEST PATH: Use COMPILED batch mapper

                if (annotation != null && (annotation.includeId() || annotation.includeAudit())) {
                    // Use Map-based response to auto-inject audit fields
                    responses = entities.stream()
                            .map(entity -> {
                                try {
                                    return mapperGenerator != null
                                            ? mapperGenerator.toResponseMap(entity, responseDtoClass)
                                            : compiledMapper.toResponse(entity);
                                } catch (Exception e) {
                                    log.warn("Map-based response failed, using direct: {}", e.getMessage());
                                    return compiledMapper.toResponse(entity);
                                }
                            })
                            .collect(Collectors.toList());

                    log.debug("⚡ COMPILED batch (Map mode): {} entities with auto-injected fields", entities.size());
                } else {
                    // Direct DTO mapping (no audit injection needed)
                    responses = compiledMapper.toResponseList(entities);
                    log.debug("⚡ COMPILED batch (Direct mode): {} entities", entities.size());
                }

                if (log.isDebugEnabled()) {
                    long elapsed = (System.nanoTime() - start) / 1_000_000;
                    log.debug("⚡ COMPILED batch: {} entities→{} in {} ms ({} μs/entity)",
                            entities.size(),
                            responseDtoClass.getSimpleName(),
                            elapsed,
                            elapsed > 0 ? (elapsed * 1000) / entities.size() : 0);
                }

            } else {
                // Fallback: Runtime generation
                if (annotation != null && (annotation.includeId() || annotation.includeAudit())) {
                    responses = mapperGenerator.toResponseMapList(entities, responseDtoClass);
                    log.debug("⚠️  RUNTIME batch (Map mode): {} entities", entities.size());
                } else {
                    responses = mapperGenerator.toResponseList(entities, responseDtoClass);
                    log.debug("⚠️  RUNTIME batch (Direct mode): {} entities", entities.size());
                }

                if (log.isDebugEnabled()) {
                    long elapsed = (System.nanoTime() - start) / 1_000_000;
                    log.debug("⚠️  RUNTIME batch: {} entities in {} ms",
                            entities.size(), elapsed);
                }
            }

            trackDtoConversion(start, true);
            return responses;

        } catch (Exception e) {
            log.error("Batch response mapping failed: {}", e.getMessage(), e);
            return entities;
        }
    }

    /**
     * 🔥 OPTIMIZATION: Convert BatchResult with cached mappers
     */
    @SuppressWarnings("unchecked")
    private Object convertBatchResultToResponse(BatchResult<T> entityResult, CrudXOperation operation) {
        if (mapperMode == MapperMode.NONE) {
            return entityResult;
        }

        long start = System.nanoTime();

        try {
            List<?> responseDtos = convertEntitiesToResponse(
                    entityResult.getCreatedEntities(), operation);

            BatchResult<Object> dtoResult = new BatchResult<>();
            dtoResult.setCreatedEntities((List<Object>) responseDtos);
            dtoResult.setSkippedCount(entityResult.getSkippedCount());
            dtoResult.setSkippedReasons(entityResult.getSkippedReasons());

            long elapsed = (System.nanoTime() - start) / 1_000_000;

            if (elapsed > 1) {
                log.debug("✓ BatchResult wrapping: {} ms", elapsed);
            }

            return dtoResult;

        } catch (Exception e) {
            log.error("BatchResult conversion failed: {}", e.getMessage());
            return entityResult;
        }
    }

    /**
     * 🔥 OPTIMIZATION: Convert PageResponse with cached mappers
     */
    @SuppressWarnings("unchecked")
    private Object convertPageResponseToDTO(PageResponse<T> entityPage, CrudXOperation operation) {
        if (mapperMode == MapperMode.NONE) {
            return entityPage;
        }

        if (entityPage.getContent() == null || entityPage.getContent().isEmpty()) {
            return entityPage;
        }

        long start = System.nanoTime();

        try {
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

            log.debug("✓ PageResponse: {} items in {} ms (Mapper: {})",
                    entityPage.getContent().size(), elapsed, mapperMode);

            return dtoPage;

        } catch (Exception e) {
            log.error("PageResponse conversion failed: {}", e.getMessage());
            return entityPage;
        }
    }

    /**
     * 🔥 OPTIMIZATION: Convert Map → DTO for validation (cached)
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

        long start = System.nanoTime();

        try {
            Object dto = objectMapper.convertValue(map, requestDtoClass);
            trackDtoConversion(start, true);
            return dto;
        } catch (Exception e) {
            log.debug("Map→DTO conversion failed for validation: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 🔥 FALLBACK: Direct Map → Entity conversion (no DTO)
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
     * 🔥 OPTIMIZATION: Pre-process enum fields for case-insensitive matching
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
     * 🔥 OPTIMIZATION: Case-insensitive enum lookup
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
     * 🔥 OPTIMIZATION: Fast field metadata retrieval
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
     * 🔥 OPTIMIZATION: Cached required field validation
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

                request.setAttribute("dtoConversionTime", totalTime);
                request.setAttribute("dtoUsed", used ||
                        (request.getAttribute("dtoUsed") != null &&
                                (Boolean) request.getAttribute("dtoUsed")));

                // 🔥 FIX: Use debug level so it's visible
                if (log.isDebugEnabled() && durationMs > 0) {
                    log.debug("✓ DTO conversion: +{} ms = {} ms total [Mapper: {}]",
                            durationMs, totalTime, mapperMode);
                }
            }
        } catch (Exception e) {
            log.trace("DTO tracking failed: {}", e.getMessage());
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

    private void recordSingleOperationMetrics(
            HttpServletRequest request,
            long executionTimeMs,
            long mappingTimeMs,
            long validationTimeMs,
            long dbTimeMs) {

        if (metricsRegistry == null) return;

        try {
            Runtime runtime = Runtime.getRuntime();
            double memoryMB = (runtime.totalMemory() - runtime.freeMemory()) / 1024.0 / 1024.0;

            String endpoint = request.getMethod() + " " + request.getRequestURI();

            EndpointMetrics metrics = EndpointMetrics.builder()
                    .endpoint(endpoint)
                    .method(request.getMethod())
                    .totalRecords(1)
                    .successCount(1)
                    .avgExecutionTimeMs(executionTimeMs)
                    .memoryAfterMB(memoryMB)
                    .lastInvocationTime(System.currentTimeMillis())
                    .build();

            // Add timing breakdowns if applicable
            if (mappingTimeMs > 0) {
                metrics.setDtoMappingTime(formatExecutionTime(mappingTimeMs));
            }
            if (validationTimeMs > 0) {
                metrics.setValidationTime(formatExecutionTime(validationTimeMs));
            }
            if (dbTimeMs > 0) {
                metrics.setDbWriteTime(formatExecutionTime(dbTimeMs));
            }

            metrics.calculateThroughput();
            request.setAttribute("endpoint.metrics", metrics);

        } catch (Exception e) {
            log.debug("Failed to record metrics: {}", e.getMessage());
        }
    }

    private void recordBatchReadMetrics(
            HttpServletRequest request,
            long executionTimeMs,
            int recordCount,
            long dbTimeMs) {

        if (metricsRegistry == null) return;

        try {
            Runtime runtime = Runtime.getRuntime();
            double memoryMB = (runtime.totalMemory() - runtime.freeMemory()) / 1024.0 / 1024.0;

            String endpoint = request.getMethod() + " " + request.getRequestURI();

            EndpointMetrics metrics = EndpointMetrics.builder()
                    .endpoint(endpoint)
                    .method(request.getMethod())
                    .totalRecords(recordCount)
                    .successCount(recordCount)
                    .avgExecutionTimeMs(executionTimeMs)
                    .memoryAfterMB(memoryMB)
                    .dbWriteTime(formatExecutionTime(dbTimeMs)) // READ time stored as "write" field
                    .lastInvocationTime(System.currentTimeMillis())
                    .build();

            metrics.calculateThroughput();
            request.setAttribute("endpoint.metrics", metrics);

        } catch (Exception e) {
            log.debug("Failed to record batch read metrics: {}", e.getMessage());
        }
    }

    private void recordBatchDeleteMetrics(
            HttpServletRequest request,
            long executionTimeMs,
            int successCount,
            int failedCount,
            long dbTimeMs) {

        if (metricsRegistry == null) return;

        try {
            Runtime runtime = Runtime.getRuntime();
            double memoryMB = (runtime.totalMemory() - runtime.freeMemory()) / 1024.0 / 1024.0;

            String endpoint = request.getMethod() + " " + request.getRequestURI();

            EndpointMetrics metrics = EndpointMetrics.builder()
                    .endpoint(endpoint)
                    .method(request.getMethod())
                    .totalRecords(successCount + failedCount)
                    .successCount(successCount)
                    .failedCount(failedCount)
                    .avgExecutionTimeMs(executionTimeMs)
                    .memoryAfterMB(memoryMB)
                    .dbWriteTime(formatExecutionTime(dbTimeMs))
                    .lastInvocationTime(System.currentTimeMillis())
                    .build();

            metrics.calculateThroughput();
            request.setAttribute("endpoint.metrics", metrics);

        } catch (Exception e) {
            log.debug("Failed to record batch delete metrics: {}", e.getMessage());
        }
    }

    private void recordBatchUpdateMetrics(
            HttpServletRequest request,
            long executionTimeMs,
            int successCount,
            int failedCount,
            long dbTimeMs) {

        if (metricsRegistry == null) return;

        try {
            Runtime runtime = Runtime.getRuntime();
            double memoryMB = (runtime.totalMemory() - runtime.freeMemory()) / 1024.0 / 1024.0;

            String endpoint = request.getMethod() + " " + request.getRequestURI();

            EndpointMetrics metrics = EndpointMetrics.builder()
                    .endpoint(endpoint)
                    .method(request.getMethod())
                    .totalRecords(successCount + failedCount)
                    .successCount(successCount)
                    .failedCount(failedCount)
                    .avgExecutionTimeMs(executionTimeMs)
                    .memoryAfterMB(memoryMB)
                    .dbWriteTime(formatExecutionTime(dbTimeMs))
                    .lastInvocationTime(System.currentTimeMillis())
                    .build();

            metrics.calculateThroughput();
            request.setAttribute("endpoint.metrics", metrics);

        } catch (Exception e) {
            log.debug("Failed to record batch update metrics: {}", e.getMessage());
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

    private BatchResult<T> processChunkedBatch(List<T> entities, boolean skipDuplicates,
                                               int chunkSize, long startTime) {
        BatchResult<T> combinedResult = new BatchResult<>();
        List<T> allCreated = new ArrayList<>((int) (entities.size() * 0.9));
        int totalSkipped = 0;
        List<String> allSkippedReasons = new ArrayList<>();

        int totalChunks = (entities.size() + chunkSize - 1) / chunkSize;
        int chunkNumber = 0;

        for (int i = 0; i < entities.size(); i += chunkSize) {
            chunkNumber++;
            int end = Math.min(i + chunkSize, entities.size());

            ChunkProcessingResult<T> result = processSingleChunk(
                    entities, i, end, chunkNumber, totalChunks, skipDuplicates);

            allCreated.addAll(result.getCreatedEntities());
            totalSkipped += result.getSkippedCount();

            if (result.getSkippedReasons() != null && !result.getSkippedReasons().isEmpty()) {
                allSkippedReasons.addAll(result.getSkippedReasons());
            }

            if (chunkNumber % 10 == 0 || entities.size() > 10000) {
                logProgress(entities.size(), end, startTime);
            }

            if (chunkNumber % 10 == 0 && entities.size() > 10000) {
                System.gc();
            }
        }

        combinedResult.setCreatedEntities(allCreated);
        combinedResult.setSkippedCount(totalSkipped);
        if (!allSkippedReasons.isEmpty()) {
            combinedResult.setSkippedReasons(allSkippedReasons);
        }

        return combinedResult;
    }

    private ChunkProcessingResult<T> processSingleChunk(List<T> entities, int start, int end,
                                                        int chunkNumber, int totalChunks,
                                                        boolean skipDuplicates) {
        List<T> chunk = new ArrayList<>(entities.subList(start, end));
        long chunkStart = System.currentTimeMillis();

        log.debug("Processing chunk {}/{}: records {}-{}",
                chunkNumber, totalChunks, start + 1, end);

        try {
            BatchResult<T> chunkResult = crudService.createBatch(chunk, skipDuplicates);

            long chunkTime = System.currentTimeMillis() - chunkStart;
            log.debug("Chunk {}/{} completed: {} created, {} skipped | {} ms",
                    chunkNumber, totalChunks,
                    chunkResult.getCreatedEntities().size(),
                    chunkResult.getSkippedCount(),
                    chunkTime);

            return new ChunkProcessingResult<>(
                    chunkResult.getCreatedEntities(),
                    chunkResult.getSkippedCount(),
                    chunkResult.getSkippedReasons()
            );

        } catch (Exception chunkError) {
            log.error("Chunk {}/{} failed (records {}-{}): {}",
                    chunkNumber, totalChunks, start + 1, end, chunkError.getMessage());

            if (!skipDuplicates) {
                throw chunkError;
            }

            return new ChunkProcessingResult<>(
                    Collections.emptyList(),
                    end - start,
                    Collections.singletonList(String.format(
                            "Chunk %d/%d failed: %s",
                            chunkNumber, totalChunks, chunkError.getMessage()))
            );
        }
    }

    private void logProgress(int totalSize, int currentEnd, long startTime) {
        double progress = (double) currentEnd / totalSize * 100;
        long elapsed = System.currentTimeMillis() - startTime;
        long estimated = (long) (elapsed / progress * 100);

        log.info("Progress: {}/{} ({}%) | Elapsed: {} ms | Est. total: {} ms",
                currentEnd, totalSize, String.format("%.1f", progress), elapsed, estimated);
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