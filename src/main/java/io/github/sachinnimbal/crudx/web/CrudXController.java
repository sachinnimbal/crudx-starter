package io.github.sachinnimbal.crudx.web;

import io.github.sachinnimbal.crudx.core.config.CrudXProperties;
import io.github.sachinnimbal.crudx.core.dto.mapper.CrudXMapperGenerator;
import io.github.sachinnimbal.crudx.core.dto.mapper.CrudXMapperRegistry;
import io.github.sachinnimbal.crudx.core.exception.DuplicateEntityException;
import io.github.sachinnimbal.crudx.core.model.CrudXBaseEntity;
import io.github.sachinnimbal.crudx.core.response.ApiResponse;
import io.github.sachinnimbal.crudx.core.response.BatchResult;
import io.github.sachinnimbal.crudx.core.response.PageResponse;
import io.github.sachinnimbal.crudx.service.CrudXService;
import io.github.sachinnimbal.crudx.web.components.*;
import jakarta.annotation.PostConstruct;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static io.github.sachinnimbal.crudx.core.enums.CrudXOperation.*;

/**
 * Base CRUD Controller - Thin orchestration layer
 * All heavy lifting delegated to specialized components
 */
@Slf4j
public abstract class CrudXController<T extends CrudXBaseEntity<ID>, ID extends Serializable>
        extends CrudXLifecycleHooks<T, ID> {

    @Autowired
    protected ApplicationContext applicationContext;

    @Autowired(required = false)
    protected CrudXMapperRegistry dtoRegistry;

    @Autowired(required = false)
    protected CrudXMapperGenerator mapperGenerator;

    @Autowired
    protected CrudXProperties crudxProperties;

    protected CrudXService<T, ID> crudService;

    // Component delegates
    private CrudXControllerHelper<T, ID> helper;
    private CrudXDTOConverter<T, ID> dtoConverter;
    private CrudXValidationHelper<T, ID> validationHelper;
    private CrudXBatchProcessor<T, ID> batchProcessor;

    private static final int LARGE_DATASET_THRESHOLD = 1000;
    private static final int DEFAULT_PAGE_SIZE = 50;

    @PostConstruct
    protected void initializeService() {
        // Initialize helper to resolve generic types
        helper = new CrudXControllerHelper<>(getClass());

        if (helper.getEntityClass() == null) {
            throw new IllegalStateException(
                    "Could not resolve entity class for controller: " + getClass().getSimpleName()
            );
        }

        // Initialize service
        initializeCrudService();

        // Initialize DTO converter
        dtoConverter = new CrudXDTOConverter<>(
                helper.getEntityClass(),
                dtoRegistry,
                mapperGenerator
        );

        // Initialize mapper (compiled or runtime)
        if (crudxProperties.getDto().isEnabled()) {
            initializeMapper();
        } else {
            log.warn("⚠️  DTO Feature is DISABLED (crudx.dto.enabled=false)");
        }

        // Initialize validation helper
        validationHelper = new CrudXValidationHelper<>(helper.getEntityClass());

        // Initialize batch processor with lifecycle callbacks
        batchProcessor = new CrudXBatchProcessor<>(
                crudService,
                dtoConverter,
                validationHelper,
                createLifecycleCallbacks()
        );

        logInitializationSummary();
    }

    private void initializeCrudService() {
        String serviceBeanName = helper.getServiceBeanName();

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
            throw new IllegalStateException("Service bean not found: " + serviceBeanName, e);
        }
    }

    private void initializeMapper() {
        String mapperBeanName = helper.getMapperBeanName();

        try {
            Object mapperBean = applicationContext.getBean(mapperBeanName);
            dtoConverter.initializeMapper(mapperBean, crudxProperties.getDto().isEnabled());
        } catch (Exception e) {
            // Fallback to runtime mapper
            dtoConverter.initializeMapper(null, crudxProperties.getDto().isEnabled());
        }
    }

    private CrudXBatchProcessor.LifecycleCallbacks<T, ID> createLifecycleCallbacks() {
        return new CrudXBatchProcessor.LifecycleCallbacks<>() {
            @Override
            public void beforeCreateBatch(List<T> entities) {
                CrudXController.this.beforeCreateBatch(entities);
            }

            @Override
            public void afterCreateBatch(List<T> entities) {
                CrudXController.this.afterCreateBatch(entities);
            }

            @Override
            public void beforeDeleteBatch(List<ID> ids) {
                CrudXController.this.beforeDeleteBatch(ids);
            }

            @Override
            public void afterDeleteBatch(List<ID> deletedIds) {
                CrudXController.this.afterDeleteBatch(deletedIds);
            }
        };
    }

    private void logInitializationSummary() {
        log.info("════════════════════════════════════════════════");
        log.info("Controller: {} | Entity: {} | Mapper: {}",
                getClass().getSimpleName(),
                helper.getEntityClass().getSimpleName(),
                dtoConverter.getMapperMode());
        log.info("════════════════════════════════════════════════");
    }

    // ==================== CRUD ENDPOINTS ====================

    @PostMapping
    public ResponseEntity<ApiResponse<?>> create(@Valid @RequestBody Map<String, Object> requestBody) {
        long startTime = System.currentTimeMillis();

        try {
            validationHelper.validateRequestBody(requestBody);

            T entity = dtoConverter.convertMapToEntity(requestBody, CREATE);
            validationHelper.validateRequiredFields(entity);

            beforeCreate(entity);
            T created = crudService.create(entity);
            afterCreate(created);

            long executionTime = System.currentTimeMillis() - startTime;
            Object response = dtoConverter.convertEntityToResponse(created, CREATE);

            log.info("Entity created with ID: {} | Time: {} ms", created.getId(), executionTime);

            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(ApiResponse.success(response, "Entity created successfully",
                            HttpStatus.CREATED, executionTime));

        } catch (DuplicateEntityException e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("Error creating entity: {} | Time: {} ms", e.getMessage(), executionTime);
            throw e; // Re-throw without wrapping
        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("Error creating entity: {} | Time: {} ms", e.getMessage(), executionTime, e);
            throw new RuntimeException("Failed to create entity: " + e.getMessage(), e);
        }
    }

    @PostMapping("/batch")
    public ResponseEntity<ApiResponse<?>> createBatch(
            @Valid @RequestBody List<Map<String, Object>> requestBodies,
            @RequestParam(required = false, defaultValue = "true") boolean skipDuplicates) {

        long startTime = System.currentTimeMillis();

        try {
            validationHelper.validateBatchRequestBody(requestBodies);
            validationHelper.validateBatchSize(requestBodies.size(), crudxProperties.getMaxBatchSize());

            T testEntity = dtoConverter.convertMapToEntity(requestBodies.get(0), BATCH_CREATE);
            validationHelper.validateRequiredFields(testEntity);

            log.info("🚀 Starting batch creation: {} entities", requestBodies.size());

            CrudXBatchProcessor.BatchCreationResult result = batchProcessor.processBatchCreation(
                    requestBodies,
                    skipDuplicates,
                    crudxProperties.getBatchSize()
            );

            // 🔥 Build enhanced response data
            Map<String, Object> responseData = batchProcessor.buildBatchResponseData(result);

            // 🔥 Enhanced message with detailed breakdown
            String message = buildBatchCreationMessage(
                    result.getSuccessCount(),
                    result.getSkipCount(),
                    result.getTotalProcessed(),
                    result.getDuplicateCount(),
                    result.getValidationFailCount()
            );

            HttpStatus status = determineBatchResponseStatus(
                    result.getSuccessCount(),
                    result.getTotalProcessed()
            );

            // 🔥 Extract warnings for response
            List<String> warnings = result.getSkipReasons() != null && !result.getSkipReasons().isEmpty()
                    ? result.getSkipReasons().subList(0, Math.min(10, result.getSkipReasons().size()))
                    : null;

            log.info("✅ Batch completed: {} | {} ms", message, result.getDuration());

            // 🔥 Use enhanced batch response
            return ResponseEntity.status(status)
                    .body(ApiResponse.batchSuccess(
                            responseData,
                            message,
                            status,
                            result.getDuration(),
                            result.getSuccessCount(),
                            result.getSkipCount(),
                            result.getDuplicateCount()
                    ));

        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("Batch creation error: {} | Time: {} ms", e.getMessage(), executionTime, e);
            throw new RuntimeException("Failed to create batch: " + e.getMessage(), e);
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<?>> getById(@PathVariable ID id) {
        long startTime = System.currentTimeMillis();
        validationHelper.validateId(id);

        try {
            T entity = crudService.findById(id);
            afterFindById(entity);

            long executionTime = System.currentTimeMillis() - startTime;
            Object response = dtoConverter.convertEntityToResponse(entity, GET_ID);

            return ResponseEntity.ok(ApiResponse.success(response,
                    "Entity retrieved successfully", executionTime));

        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("Error fetching entity {}: {} | Time: {} ms", id, e.getMessage(), executionTime, e);
            throw new RuntimeException("Failed to retrieve entity: " + e.getMessage(), e);
        }
    }

    @GetMapping
    public ResponseEntity<ApiResponse<?>> getAll(
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false, defaultValue = "ASC") String sortDirection) {

        long startTime = System.currentTimeMillis();

        try {
            long totalCount = crudService.count();

            if (totalCount > LARGE_DATASET_THRESHOLD) {
                log.warn("Large dataset ({} records) - auto-switching to pagination", totalCount);

                Pageable pageable = validationHelper.createPageable(0, DEFAULT_PAGE_SIZE, sortBy, sortDirection);
                Page<T> springPage = crudService.findAll(pageable);
                PageResponse<T> pageResponse = PageResponse.from(springPage);
                afterFindPaged(pageResponse);

                long executionTime = System.currentTimeMillis() - startTime;
                Object response = dtoConverter.convertPageResponseToDTO(pageResponse, GET_PAGED);

                return ResponseEntity.ok(ApiResponse.success(response,
                        helper.formatLargeDatasetWarning(totalCount, pageResponse.getContent().size()),
                        executionTime));
            }

            List<T> entities = sortBy != null ?
                    crudService.findAll(Sort.by(Sort.Direction.fromString(sortDirection), sortBy)) :
                    crudService.findAll();

            afterFindAll(entities);

            long executionTime = System.currentTimeMillis() - startTime;
            Object response = dtoConverter.convertEntitiesToResponse(entities, GET_ALL);

            return ResponseEntity.ok(ApiResponse.success(response,
                    helper.formatListMessage(entities.size()), executionTime));

        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("Error fetching all: {} | Time: {} ms", e.getMessage(), executionTime, e);
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
            Pageable pageable = validationHelper.createPageable(page, size, sortBy, sortDirection);
            Page<T> springPage = crudService.findAll(pageable);
            PageResponse<T> pageResponse = PageResponse.from(springPage);
            afterFindPaged(pageResponse);

            long executionTime = System.currentTimeMillis() - startTime;
            Object response = dtoConverter.convertPageResponseToDTO(pageResponse, GET_PAGED);

            return ResponseEntity.ok(ApiResponse.success(response,
                    helper.formatPageMessage(page, pageResponse.getContent().size(),
                            pageResponse.getTotalElements()),
                    executionTime));

        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("Error fetching page: {} | Time: {} ms", e.getMessage(), executionTime, e);
            throw new RuntimeException("Failed to retrieve paged data: " + e.getMessage(), e);
        }
    }

    @PatchMapping("/{id}")
    public ResponseEntity<ApiResponse<?>> update(
            @PathVariable ID id,
            @RequestBody @NotEmpty Map<String, Object> updates) {

        long startTime = System.currentTimeMillis();
        validationHelper.validateId(id);

        try {
            validationHelper.validateUpdates(updates);

            T existingEntity = crudService.findById(id);
            beforeUpdate(id, updates, existingEntity);
            T oldEntity = helper.cloneEntity(existingEntity);

            Object requestDto = dtoConverter.convertMapToDTO(updates, UPDATE);
            if (requestDto != null) {
                validationHelper.validateRequiredFields(requestDto);
            }

            T updated = crudService.update(id, updates);
            afterUpdate(updated, oldEntity);

            long executionTime = System.currentTimeMillis() - startTime;
            Object response = dtoConverter.convertEntityToResponse(updated, UPDATE);

            return ResponseEntity.ok(ApiResponse.success(response,
                    "Entity updated successfully", executionTime));

        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("Error updating {}: {} | Time: {} ms", id, e.getMessage(), executionTime, e);
            throw new RuntimeException("Failed to update entity: " + e.getMessage(), e);
        }
    }

    @PatchMapping("/batch")
    public ResponseEntity<ApiResponse<?>> updateBatch(
            @Valid @RequestBody Map<ID, Map<String, Object>> updates) {

        long startTime = System.currentTimeMillis();

        try {
            validationHelper.validateBatchUpdates(updates);

            BatchResult<T> result = batchProcessor.processBatchUpdate(updates);

            long executionTime = System.currentTimeMillis() - startTime;
            Object responseData = dtoConverter.convertBatchResultToResponse(result, BATCH_UPDATE);

            // 🔥 Enhanced message with duplicate count
            String message = buildBatchUpdateMessage(
                    result.getCreatedEntities().size(),
                    result.getSkippedCount(),
                    result.getDuplicateSkipCount()
            );

            // 🔥 Determine status based on results
            HttpStatus status = determineBatchResponseStatus(
                    result.getCreatedEntities().size(),
                    result.getTotalProcessed()
            );

            log.info("✅ Batch update: {} | {} ms", message, executionTime);

            return ResponseEntity.status(status)
                    .body(ApiResponse.success(responseData, message, status, executionTime));

        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("Batch update error: {} | Time: {} ms", e.getMessage(), executionTime, e);
            throw new RuntimeException("Failed to update batch: " + e.getMessage(), e);
        }
    }

    @GetMapping("/count")
    public ResponseEntity<ApiResponse<Long>> count() {
        long startTime = System.currentTimeMillis();

        try {
            long count = crudService.count();
            long executionTime = System.currentTimeMillis() - startTime;

            return ResponseEntity.ok(ApiResponse.success(count,
                    helper.formatCountMessage(count), executionTime));
        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("Count error: {} | Time: {} ms", e.getMessage(), executionTime, e);
            throw new RuntimeException("Failed to count entities: " + e.getMessage(), e);
        }
    }

    @GetMapping("/exists/{id}")
    public ResponseEntity<ApiResponse<Boolean>> exists(@PathVariable ID id) {
        long startTime = System.currentTimeMillis();
        validationHelper.validateId(id);

        try {
            boolean exists = crudService.existsById(id);
            long executionTime = System.currentTimeMillis() - startTime;

            return ResponseEntity.ok(ApiResponse.success(exists,
                    helper.formatExistsMessage(id, exists), executionTime));
        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("Exists check error: {} | Time: {} ms", e.getMessage(), executionTime, e);
            throw new RuntimeException("Failed to check entity existence: " + e.getMessage(), e);
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable ID id) {
        long startTime = System.currentTimeMillis();
        validationHelper.validateId(id);

        try {
            T deletedEntity = crudService.delete(id);
            beforeDelete(id, deletedEntity);
            afterDelete(id, deletedEntity);

            long executionTime = System.currentTimeMillis() - startTime;

            return ResponseEntity.ok(ApiResponse.success(null,
                    "Entity deleted successfully", executionTime));
        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("Delete error {}: {} | Time: {} ms", id, e.getMessage(), executionTime, e);
            throw new RuntimeException("Failed to delete entity: " + e.getMessage(), e);
        }
    }

    @DeleteMapping("/batch")
    public ResponseEntity<ApiResponse<BatchResult<ID>>> deleteBatch(@Valid @RequestBody List<ID> ids) {
        long startTime = System.currentTimeMillis();

        try {
            validationHelper.validateIdList(ids);

            BatchResult<T> deletionResult = batchProcessor.processBatchDelete(ids);

            List<ID> deletedIds = deletionResult.getCreatedEntities().stream()
                    .map(T::getId)
                    .collect(Collectors.toList());

            BatchResult<ID> result = new BatchResult<>();
            result.setCreatedEntities(deletedIds);
            result.setSkippedCount(deletionResult.getSkippedCount());
            result.setSkippedReasons(deletionResult.getSkippedReasons());

            long executionTime = System.currentTimeMillis() - startTime;

            String message = helper.formatBatchMessage(
                    result.getCreatedEntities().size(),
                    result.getSkippedCount(),
                    "deletion"
            );

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
            validationHelper.validateIdList(ids);
            validationHelper.validateForceDeleteSize(ids.size(), LARGE_DATASET_THRESHOLD);

            int totalDeleted = batchProcessor.processForceDelete(ids, crudxProperties.getBatchSize());

            long executionTime = System.currentTimeMillis() - startTime;

            return ResponseEntity.ok(ApiResponse.success(null,
                    String.format("%d IDs processed for deletion", totalDeleted),
                    executionTime));

        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("Force delete error: {} | Time: {} ms", e.getMessage(), executionTime, e);
            throw new RuntimeException("Failed to force delete batch: " + e.getMessage(), e);
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void displayPerformanceSummary() {
        if (!dtoConverter.isUsingCompiledMapper() && dtoConverter.isDTOEnabled()) {
            log.warn("");
            log.warn("╔════════════════════════════════════════════════════════════╗");
            log.warn("║  ⚠️  PERFORMANCE WARNING: Runtime Mapper Active          ║");
            log.warn("╠════════════════════════════════════════════════════════════╣");
            log.warn("║  Entity: {}", String.format("%-48s",
                    helper.getEntityClass().getSimpleName()) + "║");
            log.warn("║  Status: SLOW - Using reflection-based mapping            ║");
            log.warn("║                                                            ║");
            log.warn("║  💡 TO FIX: Add annotation processor to build config      ║");
            log.warn("║     Expected speedup: 100x faster after rebuild           ║");
            log.warn("╚════════════════════════════════════════════════════════════╝");
            log.warn("");
        }
    }

    // ==================== PUBLIC ACCESSORS ====================

    protected Class<T> getEntityClass() {
        return helper.getEntityClass();
    }

    protected Class<ID> getIdClass() {
        return helper.getIdClass();
    }

    protected boolean isDTOEnabled() {
        return dtoConverter.isDTOEnabled();
    }

    protected boolean isUsingCompiledMapper() {
        return dtoConverter.isUsingCompiledMapper();
    }

    protected String getMapperMode() {
        return dtoConverter.getMapperMode().name();
    }

    private String buildBatchCreationMessage(int success, int skipped, int total,
                                             int duplicates, int validationFails) {
        if (skipped == 0) {
            return String.format("All %d records created successfully", success);
        }

        StringBuilder msg = new StringBuilder();
        msg.append(String.format("Batch creation: %d/%d successful", success, total));

        if (duplicates > 0 || validationFails > 0) {
            msg.append(String.format(", %d skipped", skipped));

            List<String> reasons = new ArrayList<>();
            if (duplicates > 0) {
                reasons.add(String.format("%d duplicates", duplicates));
            }
            if (validationFails > 0) {
                reasons.add(String.format("%d validation errors", validationFails));
            }

            msg.append(" (").append(String.join(", ", reasons)).append(")");
        } else {
            msg.append(String.format(", %d skipped", skipped));
        }

        return msg.toString();
    }

    private String buildBatchUpdateMessage(int success, int skipped, Integer duplicates) {
        if (skipped == 0) {
            return String.format("All %d records updated successfully", success);
        }

        StringBuilder msg = new StringBuilder();
        msg.append(String.format("Batch update: %d successful, %d skipped", success, skipped));

        if (duplicates != null && duplicates > 0) {
            msg.append(String.format(" (%d duplicate constraint violations)", duplicates));
        }

        return msg.toString();
    }

    private HttpStatus determineBatchResponseStatus(int successCount, int totalCount) {
        if (successCount == 0) {
            return HttpStatus.BAD_REQUEST; // All failed
        }
        if (successCount < totalCount) {
            return HttpStatus.PARTIAL_CONTENT; // Some failed
        }
        return HttpStatus.CREATED; // All succeeded
    }
}