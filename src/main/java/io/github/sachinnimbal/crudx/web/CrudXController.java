package io.github.sachinnimbal.crudx.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.sachinnimbal.crudx.core.config.CrudXProperties;
import io.github.sachinnimbal.crudx.core.dto.annotations.CrudXResponse;
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

import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;
import java.util.stream.Collectors;

import static io.github.sachinnimbal.crudx.core.enums.CrudXOperation.*;

/**
 * 🔥 FINAL FIXED VERSION - Enterprise-Grade CRUD Controller
 * <p>
 * Key Features:
 * - ✅ Smart DTO → Entity mapping with @CrudXField support
 * - ✅ Auto Entity response when no Response DTO defined
 * - ✅ Bidirectional field resolution (DTO↔Entity)
 * - ✅ Zero-configuration for simple cases
 * - ✅ Full annotation support for complex scenarios
 *
 * @author Sachin Nimbal
 * @since 1.0.2
 */
@Slf4j
public abstract class CrudXController<T extends CrudXBaseEntity<ID>, ID extends Serializable> {

    @Autowired
    protected ApplicationContext applicationContext;

    @Autowired(required = false)
    protected CrudXMapperRegistry dtoRegistry;

    @Autowired(required = false)
    protected CrudXMapperGenerator mapperGenerator;

    protected CrudXService<T, ID> crudService;
    protected CrudXMapper<T, ?, ?> dtoMapper;

    private Class<T> entityClass;
    private Class<ID> idClass;
    private boolean dtoPseudoEnabled = false;

    @Autowired
    protected CrudXProperties crudxProperties;

    private ObjectMapper objectMapper;

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

        // Initialize ObjectMapper
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

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

        // Initialize DTO mapper if available
        if (dtoRegistry != null && dtoRegistry.hasDTOMapping(entityClass)) {
            Optional<CrudXMapper<T, Object, Object>> mapper = dtoRegistry.getMapper(entityClass);
            if (mapper.isPresent()) {
                dtoMapper = mapper.get();
                dtoPseudoEnabled = true;
                log.info("✓ DTO mapping enabled for entity: {} (runtime generation)",
                        entityClass.getSimpleName());
            }
        } else {
            log.debug("No DTO mappings found for entity: {} - using entity directly",
                    entityClass.getSimpleName());
        }
    }

    /**
     * CREATE - Enhanced with smart DTO mapping
     */
    @PostMapping
    public ResponseEntity<ApiResponse<?>> create(@Valid @RequestBody Map<String, Object> requestBody) {
        long startTime = System.currentTimeMillis();

        try {
            log.debug("Creating entity: {} (DTO enabled: {})",
                    entityClass.getSimpleName(), dtoPseudoEnabled);

            if (requestBody == null || requestBody.isEmpty()) {
                throw new IllegalArgumentException("Request body cannot be null or empty");
            }

            T entity;

            // 🔥 FIX: Use runtime mapper for DTO → Entity conversion
            if (dtoPseudoEnabled && mapperGenerator != null) {
                entity = convertMapToEntity(requestBody, CREATE);
            } else {
                // Legacy Mode: Direct entity conversion
                entity = convertMapToEntityDirectly(requestBody);
            }

            beforeCreate(entity);
            T created = crudService.create(entity);
            afterCreate(created);

            long executionTime = System.currentTimeMillis() - startTime;

            // 🔥 FIX: Smart response conversion (entity if no Response DTO)
            Object response = convertEntityToResponse(created, CREATE);

            log.info("Entity created successfully with ID: {} | Time taken: {} ms",
                    created.getId(), executionTime);

            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(ApiResponse.success(response, "Entity created successfully",
                            HttpStatus.CREATED, executionTime));

        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("Error creating entity: {} | Time taken: {} ms",
                    e.getMessage(), executionTime, e);
            throw new RuntimeException("Failed to create entity: " + e.getMessage(), e);
        }
    }

    /**
     * BATCH CREATE - Enhanced with DTO support
     */
    @PostMapping("/batch")
    public ResponseEntity<ApiResponse<?>> createBatch(
            @Valid @RequestBody List<Map<String, Object>> requestBodies,
            @RequestParam(required = false, defaultValue = "true") boolean skipDuplicates) {

        long startTime = System.currentTimeMillis();

        try {
            log.debug("Creating batch of {} entities (DTO enabled: {}, skipDuplicates: {})",
                    requestBodies.size(), dtoPseudoEnabled, skipDuplicates);

            if (requestBodies.isEmpty()) {
                throw new IllegalArgumentException("Entity list cannot be null or empty");
            }

            List<T> entities;

            if (dtoPseudoEnabled && mapperGenerator != null) {
                entities = requestBodies.stream()
                        .map(map -> convertMapToEntity(map, BATCH_CREATE))
                        .collect(Collectors.toList());
            } else {
                entities = requestBodies.stream()
                        .map(this::convertMapToEntityDirectly)
                        .collect(Collectors.toList());
            }

            beforeCreateBatch(entities);

            final int MAX_BATCH_SIZE = crudxProperties.getMaxBatchSize();
            if (entities.size() > MAX_BATCH_SIZE) {
                throw new IllegalArgumentException(
                        String.format("Batch size exceeds maximum limit of %d. Current size: %d",
                                MAX_BATCH_SIZE, entities.size())
                );
            }

            BatchResult<T> result;
            if (entities.size() > 100) {
                result = processChunkedBatch(entities, skipDuplicates,
                        crudxProperties.getBatchSize(), startTime);
            } else {
                result = crudService.createBatch(entities, skipDuplicates);
            }

            afterCreateBatch(result.getCreatedEntities());

            long executionTime = System.currentTimeMillis() - startTime;
            double recordsPerSecond = (result.getCreatedEntities().size() * 1000.0) / executionTime;

            Object responseData = convertBatchResultToResponse(result, BATCH_CREATE);

            String message = result.hasSkipped() ?
                    String.format("Batch creation completed: %d created, %d skipped | Performance: %.0f records/sec",
                            result.getCreatedEntities().size(), result.getSkippedCount(), recordsPerSecond) :
                    String.format("%d entities created successfully | Performance: %.0f records/sec",
                            result.getCreatedEntities().size(), recordsPerSecond);

            log.info("{} | Total time: {} ms", message, executionTime);

            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(ApiResponse.success(responseData, message, HttpStatus.CREATED, executionTime));

        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("Error creating batch: {} | Time taken: {} ms",
                    e.getMessage(), executionTime, e);
            throw new RuntimeException("Failed to create batch: " + e.getMessage(), e);
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<?>> getById(@PathVariable ID id) {
        long startTime = System.currentTimeMillis();
        validateId(id);

        try {
            log.debug("Fetching entity by ID: {} (DTO enabled: {})", id, dtoPseudoEnabled);

            T entity = crudService.findById(id);
            afterFindById(entity);

            long executionTime = System.currentTimeMillis() - startTime;

            Object response = convertEntityToResponse(entity, GET_ID);

            log.info("Entity found with ID: {} | Time taken: {} ms", id, executionTime);

            return ResponseEntity.ok(ApiResponse.success(response,
                    "Entity retrieved successfully", executionTime));

        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("Error fetching entity by ID {}: {} | Time taken: {} ms",
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
            log.debug("Fetching all entities (DTO enabled: {})", dtoPseudoEnabled);

            long totalCount = crudService.count();

            if (totalCount > LARGE_DATASET_THRESHOLD) {
                log.warn("Large dataset detected ({} records). Auto-switching to paginated response",
                        totalCount);

                Pageable pageable = createPageable(0, DEFAULT_PAGE_SIZE, sortBy, sortDirection);
                Page<T> springPage = crudService.findAll(pageable);
                PageResponse<T> pageResponse = PageResponse.from(springPage);
                afterFindPaged(pageResponse);

                long executionTime = System.currentTimeMillis() - startTime;

                Object response = convertPageResponseToDTO(pageResponse, GET_PAGED);

                return ResponseEntity.ok(ApiResponse.success(response,
                        String.format("Large dataset detected (%d total records). " +
                                        "Returning first %d records. Use /paged endpoint for more data.",
                                totalCount, pageResponse.getContent().size()),
                        executionTime));
            }

            List<T> entities = sortBy != null ?
                    crudService.findAll(Sort.by(Sort.Direction.fromString(sortDirection), sortBy)) :
                    crudService.findAll();

            afterFindAll(entities);

            long executionTime = System.currentTimeMillis() - startTime;

            Object response = convertEntitiesToResponse(entities, GET_ALL);

            log.info("Retrieved {} entities | Time taken: {} ms", entities.size(), executionTime);

            return ResponseEntity.ok(ApiResponse.success(response,
                    String.format("Retrieved %d entities", entities.size()),
                    executionTime));

        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("Error fetching all entities: {} | Time taken: {} ms",
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
            log.debug("Fetching paged entities (DTO enabled: {})", dtoPseudoEnabled);

            validatePagination(page, size);

            Pageable pageable = createPageable(page, size, sortBy, sortDirection);
            Page<T> springPage = crudService.findAll(pageable);
            PageResponse<T> pageResponse = PageResponse.from(springPage);
            afterFindPaged(pageResponse);

            long executionTime = System.currentTimeMillis() - startTime;

            Object response = convertPageResponseToDTO(pageResponse, GET_PAGED);

            log.info("Found page of {} entities (total: {}) | Time taken: {} ms",
                    pageResponse.getContent().size(), pageResponse.getTotalElements(), executionTime);

            String message = String.format("Retrieved page %d with %d elements (total: %d)",
                    page, pageResponse.getContent().size(), pageResponse.getTotalElements());

            return ResponseEntity.ok(ApiResponse.success(response, message, executionTime));

        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("Error fetching paged entities: {} | Time taken: {} ms",
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
            log.debug("Updating entity with ID: {} (DTO enabled: {})", id, dtoPseudoEnabled);

            if (updates == null || updates.isEmpty()) {
                throw new IllegalArgumentException("Update data cannot be null or empty");
            }

            T existingEntity = crudService.findById(id);
            beforeUpdate(id, updates, existingEntity);
            T oldEntity = cloneEntity(existingEntity);

            T updated = crudService.update(id, updates);
            afterUpdate(updated, oldEntity);

            long executionTime = System.currentTimeMillis() - startTime;

            Object response = convertEntityToResponse(updated, UPDATE);

            log.info("Entity updated successfully with ID: {} | Time taken: {} ms",
                    id, executionTime);

            return ResponseEntity.ok(ApiResponse.success(response,
                    "Entity updated successfully", executionTime));

        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("Error updating entity with ID {}: {} | Time taken: {} ms",
                    id, e.getMessage(), executionTime, e);
            throw new RuntimeException("Failed to update entity: " + e.getMessage(), e);
        }
    }

    @PatchMapping("/batch")
    public ResponseEntity<ApiResponse<?>> updateBatch(
            @Valid @RequestBody Map<ID, Map<String, Object>> updates) {

        long startTime = System.currentTimeMillis();

        try {
            log.debug("Processing batch update for {} entities (DTO enabled: {})",
                    updates.size(), dtoPseudoEnabled);

            if (updates.isEmpty()) {
                throw new IllegalArgumentException("Updates map cannot be empty");
            }

            BatchResult<T> result = crudService.updateBatch(updates);

            long executionTime = System.currentTimeMillis() - startTime;

            Object responseData = convertBatchResultToResponse(result, BATCH_UPDATE);

            String message = result.hasSkipped() ?
                    String.format("Batch update completed: %d updated, %d skipped",
                            result.getCreatedEntities().size(), result.getSkippedCount()) :
                    String.format("%d entities updated successfully",
                            result.getCreatedEntities().size());

            return ResponseEntity.ok(ApiResponse.success(responseData, message, executionTime));

        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("Error in batch update: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to update batch: " + e.getMessage(), e);
        }
    }

    @GetMapping("/count")
    public ResponseEntity<ApiResponse<Long>> count() {
        long startTime = System.currentTimeMillis();

        try {
            log.debug("Counting all entities");
            long count = crudService.count();

            long executionTime = System.currentTimeMillis() - startTime;

            log.info("Total entities count: {} | Time taken: {} ms", count, executionTime);

            return ResponseEntity.ok(ApiResponse.success(count,
                    String.format("Total count: %d", count),
                    executionTime));
        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("Error counting entities: {} | Time taken: {} ms", e.getMessage(), executionTime, e);
            throw new RuntimeException("Failed to count entities: " + e.getMessage(), e);
        }
    }

    @GetMapping("/exists/{id}")
    public ResponseEntity<ApiResponse<Boolean>> exists(@PathVariable ID id) {
        long startTime = System.currentTimeMillis();
        validateId(id);
        try {
            log.debug("Checking existence of entity with ID: {}", id);

            boolean exists = crudService.existsById(id);

            long executionTime = System.currentTimeMillis() - startTime;

            log.info("Entity with ID {} exists: {} | Time taken: {} ms", id, exists, executionTime);

            return ResponseEntity.ok(ApiResponse.success(exists,
                    String.format("Entity %s", exists ? "exists" : "does not exist"),
                    executionTime));
        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("Error checking entity existence with ID {}: {} | Time taken: {} ms",
                    id, e.getMessage(), executionTime, e);
            throw new RuntimeException("Failed to check entity existence: " + e.getMessage(), e);
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable ID id) {
        long startTime = System.currentTimeMillis();
        validateId(id);
        try {
            log.debug("Deleting entity with ID: {}", id);

            T deletedEntity = crudService.delete(id);
            beforeDelete(id, deletedEntity);
            afterDelete(id, deletedEntity);

            long executionTime = System.currentTimeMillis() - startTime;

            log.info("Entity deleted successfully with ID: {} | Time taken: {} ms", id, executionTime);

            return ResponseEntity.ok(ApiResponse.success(null, "Entity deleted successfully", executionTime));
        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("Error deleting entity with ID {}: {} | Time taken: {} ms",
                    id, e.getMessage(), executionTime, e);
            throw new RuntimeException("Failed to delete entity: " + e.getMessage(), e);
        }
    }

    @DeleteMapping("/batch")
    public ResponseEntity<ApiResponse<BatchResult<ID>>> deleteBatch(@Valid @RequestBody List<ID> ids) {
        long startTime = System.currentTimeMillis();

        try {
            log.debug("Processing batch deletion request for {} IDs", ids.size());

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
                    ? String.format("Batch deletion completed: %d deleted, %d skipped (not found)",
                    result.getCreatedEntities().size(), result.getSkippedCount())
                    : String.format("Batch deletion completed: %d entities deleted successfully",
                    result.getCreatedEntities().size());

            log.info("{} | Time taken: {} ms", message, executionTime);

            return ResponseEntity.ok(ApiResponse.success(result, message, executionTime));

        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("Error deleting batch: {} | Time taken: {} ms", e.getMessage(), executionTime, e);
            throw new RuntimeException("Failed to delete batch: " + e.getMessage(), e);
        }
    }

    @DeleteMapping("/batch/force")
    public ResponseEntity<ApiResponse<Void>> deleteBatchForce(@Valid @RequestBody List<ID> ids) {
        long startTime = System.currentTimeMillis();

        try {
            log.debug("Force deleting batch of {} entities (skip existence check)", ids.size());

            if (ids.isEmpty()) {
                throw new IllegalArgumentException("ID list cannot be null or empty");
            }

            if (ids.size() > LARGE_DATASET_THRESHOLD) {
                throw new IllegalArgumentException(
                        String.format("Cannot force delete more than %d records at once. " +
                                        "Current request: %d IDs. Use regular /batch endpoint or multiple requests.",
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

                log.debug("Force deleted batch {}/{} entities (memory optimized)", totalDeleted, ids.size());
            }

            afterDeleteBatch(actuallyDeletedIds);

            long executionTime = System.currentTimeMillis() - startTime;

            log.info("Force batch deletion completed: {} IDs processed (may include non-existent IDs) | Time taken: {} ms",
                    totalDeleted, executionTime);

            return ResponseEntity.ok(ApiResponse.success(null,
                    String.format("%d IDs processed for deletion (existence not verified)", totalDeleted),
                    executionTime));

        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("Error force deleting batch: {} | Time taken: {} ms", e.getMessage(), executionTime, e);
            throw new RuntimeException("Failed to force delete batch: " + e.getMessage(), e);
        }
    }

    // ==================== 🔥 FIXED DTO CONVERSION METHODS ====================

    @SuppressWarnings("unchecked")
    private T convertMapToEntity(Map<String, Object> map, CrudXOperation operation) {
        if (mapperGenerator == null || dtoRegistry == null) {
            log.debug("Mapper not available, using direct conversion");
            return convertMapToEntityDirectly(map);
        }

        try {
            // 🔥 KEY FIX: Look up Request DTO class from registry
            Optional<Class<?>> requestDtoClassOpt = dtoRegistry.getRequestDTO(entityClass, operation);

            if (requestDtoClassOpt.isEmpty()) {
                log.debug("No Request DTO registered for {} operation {}, using direct conversion",
                        entityClass.getSimpleName(), operation);
                return convertMapToEntityDirectly(map);
            }

            Class<?> requestDtoClass = requestDtoClassOpt.get();

            log.debug("🔄 Using DTO flow: Map → {} → {}",
                    requestDtoClass.getSimpleName(),
                    entityClass.getSimpleName());

            // Step 1: Convert JSON Map → Request DTO object
            Object requestDto = objectMapper.convertValue(map, requestDtoClass);

            log.debug("✓ Step 1: Map → Request DTO ({})",
                    requestDtoClass.getSimpleName());

            // Step 2: Use CrudXMapperGenerator to convert Request DTO → Entity
            // This is where @CrudXField(source="...") annotations are applied
            T entity = mapperGenerator.toEntity(requestDto, entityClass);

            log.debug("✓ Step 2: Request DTO → Entity (mapped {} fields)",
                    countNonNullFields(entity));

            return entity;

        } catch (Exception e) {
            log.error("DTO mapping failed: {}, falling back to direct conversion",
                    e.getMessage(), e);
            return convertMapToEntityDirectly(map);
        }
    }

    /**
     * Direct Map → Entity conversion (fallback when no DTO is configured)
     */
    private T convertMapToEntityDirectly(Map<String, Object> map) {
        try {
            log.debug("Direct conversion: Map → {}", entityClass.getSimpleName());
            return objectMapper.convertValue(map, entityClass);
        } catch (Exception e) {
            log.error("Failed to convert map to entity {}: {}",
                    entityClass.getSimpleName(), e.getMessage());
            throw new IllegalArgumentException(
                    "Invalid request body format for " + entityClass.getSimpleName(), e);
        }
    }

    /**
     * Helper: Count non-null fields for debugging
     */
    private int countNonNullFields(Object obj) {
        if (obj == null) return 0;

        int count = 0;
        try {
            for (Field field : obj.getClass().getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) ||
                        Modifier.isFinal(field.getModifiers())) {
                    continue;
                }
                field.setAccessible(true);
                if (field.get(obj) != null) {
                    count++;
                }
            }
        } catch (Exception e) {
            // Ignore
        }
        return count;
    }

    @SuppressWarnings("unchecked")
    private Object convertEntityToResponse(T entity, CrudXOperation operation) {
        if (entity == null) {
            return null;
        }

        // Check if Response DTO exists
        if (dtoRegistry == null || !dtoRegistry.hasDTOMapping(entityClass)) {
            log.debug("✓ No Response DTO configured - returning entity directly");
            return entity;
        }

        Optional<Class<?>> responseDtoClass = dtoRegistry.getResponseDTO(entityClass, operation);

        if (responseDtoClass.isPresent() && mapperGenerator != null) {
            try {
                Class<?> dtoClass = responseDtoClass.get();
                CrudXResponse annotation = dtoClass.getAnnotation(CrudXResponse.class);

                // Use Map-based response for auto-inclusion of ID/Audit fields
                if (annotation != null && (annotation.includeId() || annotation.includeAudit())) {
                    Map<String, Object> responseMap = mapperGenerator.toResponseMap(entity, dtoClass);
                    log.debug("✓ Used Map-based response with auto-injected fields");
                    return responseMap;
                }

                // Standard DTO mapping
                Object response = mapperGenerator.toResponse(entity, dtoClass);
                log.debug("✓ Entity→DTO mapping completed successfully");
                return response;

            } catch (Exception e) {
                log.error("Response mapper failed: {}", e.getMessage(), e);
            }
        }

        // 🔥 KEY FIX: Return entity directly when no Response DTO or mapping failed
        log.debug("✓ Returning entity directly (no Response DTO for operation {})", operation);
        return entity;
    }

    /**
     * 🔥 CRITICAL FIX: Convert Entity list to Response list
     * Returns entities directly if no Response DTO is configured
     */
    @SuppressWarnings("unchecked")
    private List<?> convertEntitiesToResponse(List<T> entities, CrudXOperation operation) {
        if (entities == null || entities.isEmpty()) {
            return entities;
        }

        if (dtoRegistry == null || !dtoRegistry.hasDTOMapping(entityClass)) {
            log.debug("✓ No Response DTO configured - returning {} entities directly", entities.size());
            return entities;
        }

        Optional<Class<?>> responseDtoClass = dtoRegistry.getResponseDTO(entityClass, operation);

        if (responseDtoClass.isPresent() && mapperGenerator != null) {
            try {
                Class<?> dtoClass = responseDtoClass.get();
                CrudXResponse annotation = dtoClass.getAnnotation(CrudXResponse.class);

                if (annotation != null && (annotation.includeId() || annotation.includeAudit())) {
                    List<Map<String, Object>> responseMaps =
                            mapperGenerator.toResponseMapList(entities, dtoClass);
                    log.debug("✓ Used Map-based response list with auto-injected fields");
                    return responseMaps;
                }

                List<?> responses = mapperGenerator.toResponseList(entities, dtoClass);
                log.debug("✓ Converted {} entities to Response DTOs", entities.size());
                return responses;

            } catch (Exception e) {
                log.error("Response list mapper failed: {}", e.getMessage(), e);
            }
        }

        log.debug("✓ Returning {} entities directly (no Response DTO)", entities.size());
        return entities;
    }

    /**
     * Convert BatchResult to Response
     */
    @SuppressWarnings("unchecked")
    private Object convertBatchResultToResponse(BatchResult<T> entityResult, CrudXOperation operation) {
        if (dtoRegistry == null || !dtoRegistry.hasDTOMapping(entityClass)) {
            return entityResult;
        }

        List<?> responseDtos = convertEntitiesToResponse(entityResult.getCreatedEntities(), operation);

        BatchResult<Object> dtoResult = new BatchResult<>();
        dtoResult.setCreatedEntities((List<Object>) responseDtos);
        dtoResult.setSkippedCount(entityResult.getSkippedCount());
        dtoResult.setSkippedReasons(entityResult.getSkippedReasons());

        return dtoResult;
    }

    /**
     * Convert PageResponse to DTO
     */
    @SuppressWarnings("unchecked")
    private Object convertPageResponseToDTO(PageResponse<T> entityPage, CrudXOperation operation) {
        if (dtoRegistry == null || !dtoRegistry.hasDTOMapping(entityClass)) {
            return entityPage;
        }

        List<?> dtoContent = convertEntitiesToResponse(entityPage.getContent(), operation);

        return PageResponse.builder()
                .content((List<Object>) dtoContent)
                .currentPage(entityPage.getCurrentPage())
                .pageSize(entityPage.getPageSize())
                .totalElements(entityPage.getTotalElements())
                .totalPages(entityPage.getTotalPages())
                .first(entityPage.isFirst())
                .last(entityPage.isLast())
                .empty(entityPage.isEmpty())
                .build();
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
            log.warn("Could not clone entity for comparison", e);
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
        throw new IllegalStateException("Unknown entity database type for class: " +
                entityClass.getSimpleName());
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
            log.error("Error resolving generic types", e);
        }
    }

    @Data
    @AllArgsConstructor
    private static class ChunkProcessingResult<T> {
        private List<T> createdEntities;
        private int skippedCount;
        private List<String> skippedReasons;
    }

    private BatchResult<T> processChunkedBatch(List<T> entities,
                                               boolean skipDuplicates,
                                               int chunkSize,
                                               long startTime) {
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

    private ChunkProcessingResult<T> processSingleChunk(List<T> entities,
                                                        int start,
                                                        int end,
                                                        int chunkNumber,
                                                        int totalChunks,
                                                        boolean skipDuplicates) {
        List<T> chunk = new ArrayList<>(entities.subList(start, end));
        long chunkStart = System.currentTimeMillis();

        log.debug("Processing chunk {}/{}: records {}-{}",
                chunkNumber, totalChunks, start + 1, end);

        try {
            BatchResult<T> chunkResult = crudService.createBatch(chunk, skipDuplicates);

            long chunkTime = System.currentTimeMillis() - chunkStart;
            log.debug("Chunk {}/{} completed: {} created, {} skipped | Time: {} ms",
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
            log.error("Error processing chunk {}/{} (records {}-{}): {}",
                    chunkNumber, totalChunks, start + 1, end, chunkError.getMessage());

            if (!skipDuplicates) {
                throw chunkError;
            }

            return new ChunkProcessingResult<>(
                    Collections.emptyList(),
                    end - start,
                    Collections.singletonList(String.format(
                            "Chunk %d/%d (records %d-%d) failed: %s",
                            chunkNumber, totalChunks, start + 1, end, chunkError.getMessage()))
            );
        }
    }

    private void logProgress(int totalSize, int currentEnd, long startTime) {
        double progress = (double) currentEnd / totalSize * 100;
        long elapsed = System.currentTimeMillis() - startTime;
        long estimated = (long) (elapsed / progress * 100);

        log.info("Progress: {}/{} records ({}%) | Elapsed: {} ms | Estimated total: {} ms",
                currentEnd, totalSize, String.format("%.1f", progress), elapsed, estimated);
    }

    protected Class<T> getEntityClass() {
        return entityClass;
    }

    protected Class<ID> getIdClass() {
        return idClass;
    }

    /**
     * Check if DTO mapping is enabled for this controller.
     */
    protected boolean isDTOEnabled() {
        return dtoPseudoEnabled;
    }

    // ===== Lifecycle Hook Methods - Override these for custom logic =====

    /**
     * Called before creating a single entity.
     * Override this method to add custom validation or data transformation.
     *
     * @param entity the entity about to be created
     */
    protected void beforeCreate(T entity) {
        // Default: no-op - override in subclass
    }

    /**
     * Called after successfully creating a single entity.
     * Override this method to trigger notifications, cache updates, etc.
     *
     * @param entity the newly created entity with generated ID
     */
    protected void afterCreate(T entity) {
        // Default: no-op - override in subclass
    }

    /**
     * Called before creating a batch of entities.
     * Override this method to add batch-level validation or preprocessing.
     *
     * @param entities the list of entities about to be created
     */
    protected void beforeCreateBatch(List<T> entities) {
        // Default: no-op - override in subclass
    }

    /**
     * Called after successfully creating a batch of entities.
     * Override this method for batch-level post-processing.
     *
     * @param entities the list of successfully created entities
     */
    protected void afterCreateBatch(List<T> entities) {
        // Default: no-op - override in subclass
    }

    /**
     * Called before updating an entity.
     * Override this method to add custom validation or modify update data.
     *
     * @param id             the ID of the entity being updated
     * @param updates        the map of field updates
     * @param existingEntity the current state of the entity
     */
    protected void beforeUpdate(ID id, Map<String, Object> updates, T existingEntity) {
        // Default: no-op - override in subclass
    }

    /**
     * Called after successfully updating an entity.
     * Override this method to track changes, send notifications, etc.
     *
     * @param updatedEntity the entity after update
     * @param oldEntity     the entity before update (may be null if cloning failed)
     */
    protected void afterUpdate(T updatedEntity, T oldEntity) {
        // Default: no-op - override in subclass
    }

    /**
     * Called AFTER the entity is deleted but BEFORE the transaction commits.
     * The entity parameter contains the entity state before deletion.
     *
     * @param id            the ID of the entity that was deleted
     * @param deletedEntity the entity as it existed before deletion
     */
    protected void beforeDelete(ID id, T deletedEntity) {
        // Default: no-op - override in subclass
    }

    /**
     * Called after successfully deleting a single entity.
     * Override this method to clean up related data, invalidate caches, etc.
     *
     * @param id            the ID of the deleted entity
     * @param deletedEntity the entity that was deleted (state before deletion)
     */
    protected void afterDelete(ID id, T deletedEntity) {
        // Default: no-op - override in subclass
    }

    /**
     * Called before deleting a batch of entities.
     * Override this method to add batch deletion validation.
     *
     * @param ids the list of IDs about to be deleted
     */
    protected void beforeDeleteBatch(List<ID> ids) {
        // Default: no-op - override in subclass
    }

    /**
     * Called after successfully deleting a batch of entities.
     * Override this method for batch-level cleanup operations.
     *
     * @param deletedIds the list of IDs that were successfully deleted
     */
    protected void afterDeleteBatch(List<ID> deletedIds) {
        // Default: no-op - override in subclass
    }

    /**
     * Called after successfully finding an entity by ID.
     * Override this method to add post-fetch processing.
     *
     * @param entity the entity that was found
     */
    protected void afterFindById(T entity) {
        // Default: no-op - override in subclass
    }

    /**
     * Called after successfully finding all entities.
     * Override this method to add post-fetch processing for full lists.
     *
     * @param entities the list of entities that were found
     */
    protected void afterFindAll(List<T> entities) {
        // Default: no-op - override in subclass
    }

    /**
     * Called after successfully finding a page of entities.
     * Override this method to add post-fetch processing for paginated results.
     *
     * @param pageResponse the page response containing the entities
     */
    protected void afterFindPaged(PageResponse<T> pageResponse) {
        // Default: no-op - override in subclass
    }
}