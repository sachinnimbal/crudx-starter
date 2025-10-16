/*
 * Copyright 2025 Sachin Nimbal
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.sachinnimbal.crudx.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.sachinnimbal.crudx.core.annotations.dto.CrudXRequestDto;
import io.github.sachinnimbal.crudx.core.annotations.dto.CrudXResponseDto;
import io.github.sachinnimbal.crudx.core.enums.DatabaseType;
import io.github.sachinnimbal.crudx.core.enums.OperationType;
import io.github.sachinnimbal.crudx.core.exception.EntityNotFoundException;
import io.github.sachinnimbal.crudx.core.model.CrudXBaseEntity;
import io.github.sachinnimbal.crudx.core.model.CrudXMongoEntity;
import io.github.sachinnimbal.crudx.core.model.CrudXMySQLEntity;
import io.github.sachinnimbal.crudx.core.model.CrudXPostgreSQLEntity;
import io.github.sachinnimbal.crudx.core.response.ApiResponse;
import io.github.sachinnimbal.crudx.core.response.BatchResult;
import io.github.sachinnimbal.crudx.core.response.PageResponse;
import io.github.sachinnimbal.crudx.dto.mapper.CrudXDtoMapper;
import io.github.sachinnimbal.crudx.dto.registry.CrudXDtoRegistry;
import io.github.sachinnimbal.crudx.service.CrudXService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
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
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static io.github.sachinnimbal.crudx.core.enums.DatabaseType.*;

/**
 * Base REST controller providing zero-boilerplate CRUD operations with DTO support.
 *
 * <p><b>Usage Examples:</b></p>
 *
 * <pre>
 * // Example 1: Separate DTO files (Traditional)
 * {@literal @}RestController
 * {@literal @}RequestMapping("/api/users")
 * public class UserController extends CrudXController&lt;User, String&gt; {
 *     // All CRUD endpoints auto-generated with DTO mapping
 * }
 *
 * // DTOs in separate files:
 * {@literal @}CrudXRequestDto(entity = User.class, operations = CREATE)
 * public class UserCreateRequest { ... }
 *
 * {@literal @}CrudXResponseDto(entity = User.class, operations = {GET_BY_ID, GET_ALL})
 * public class UserResponse { ... }
 *
 * // Example 2: Inner class DTOs (All-in-one)
 * {@literal @}RestController
 * {@literal @}RequestMapping("/api/products")
 * public class ProductController extends CrudXController&lt;Product, Long&gt; {
 *
 *     {@literal @}Entity
 *     public static class Product extends CrudXMySQLEntity&lt;Long&gt; {
 *         private String name;
 *         private BigDecimal price;
 *     }
 *
 *     {@literal @}CrudXRequestDto(entity = Product.class, operations = CREATE)
 *     public static class ProductCreateRequest {
 *         private String name;
 *         private BigDecimal price;
 *     }
 *
 *     {@literal @}CrudXResponseDto(entity = Product.class, operations = {GET_BY_ID, GET_ALL, CREATE})
 *     public static class ProductResponse {
 *         private Long id;
 *         private String name;
 *         private BigDecimal price;
 *     }
 * }
 *
 * // Example 3: Mixed approach
 * {@literal @}RestController
 * {@literal @}RequestMapping("/api/orders")
 * public class OrderController extends CrudXController&lt;Order, String&gt; {
 *
 *     // Entity in separate file, DTOs here
 *     {@literal @}CrudXRequestDto(entity = Order.class, operations = CREATE)
 *     public static class CreateOrderRequest { ... }
 *
 *     {@literal @}CrudXResponseDto(entity = Order.class, operations = {GET_BY_ID, GET_ALL})
 *     public static class OrderSummary { ... }
 * }
 * </pre>
 * <p><b>DTO Customization:</b></p>
 * <ul>
 *   <li>CREATE: Use @CrudXRequestDto for input, @CrudXResponseDto for output (or entity if not defined)</li>
 *   <li>UPDATE: Use @CrudXRequestDto for input, @CrudXResponseDto for output (or entity if not defined)</li>
 *   <li>GET_BY_ID, GET_ALL, GET_PAGED: Use @CrudXResponseDto (or entity if not defined)</li>
 *   <li>DELETE, COUNT, EXISTS: No DTO customization needed (simple responses)</li>
 * </ul>
 *
 * @param <T>  the entity type
 * @param <ID> the ID type
 * @author Sachin Nimbal
 */
@Slf4j
public abstract class CrudXController<T extends CrudXBaseEntity<ID>, ID extends Serializable> {

    @Autowired
    protected ApplicationContext applicationContext;

    protected CrudXService<T, ID> crudService;

    @Autowired(required = false)
    private CrudXDtoRegistry crudXDtoRegistry;

    @Autowired(required = false)
    private CrudXDtoMapper crudXDtoMapper;

    @Autowired
    private ObjectMapper objectMapper;

    private Class<T> entityClass;
    private Class<ID> idClass;

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

        // Scan for inner class DTOs
        scanInnerClassDtos();

        // Determine DatabaseType based on entity class lineage
        DatabaseType databaseType = getDatabaseType();

        // Use entity name + database type for bean name
        String serviceBeanName = Character.toLowerCase(entityClass.getSimpleName().charAt(0)) +
                entityClass.getSimpleName().substring(1) + "Service" + databaseType.name().toLowerCase();

        try {
            @SuppressWarnings("unchecked")
            CrudXService<T, ID> service = (CrudXService<T, ID>)
                    applicationContext.getBean(serviceBeanName, CrudXService.class);
            crudService = service;

            log.info("✓ Controller initialized: {} -> Service: {} (auto-wired)",
                    getClass().getSimpleName(),
                    serviceBeanName);
        } catch (Exception e) {
            log.error("Failed to initialize service for controller: {}. Expected service bean: {}",
                    getClass().getSimpleName(),
                    serviceBeanName);
            throw new IllegalStateException(
                    "Service bean not found: " + serviceBeanName +
                            ". Ensure entity extends CrudXJPAEntity or CrudXMongoEntity", e
            );
        }
    }

    /**
     * Scan for inner class DTOs and register them
     */
    private void scanInnerClassDtos() {
        if (crudXDtoRegistry == null) {
            return;
        }

        try {
            Class<?>[] innerClasses = getClass().getDeclaredClasses();

            for (Class<?> innerClass : innerClasses) {
                if (innerClass.isAnnotationPresent(
                        CrudXRequestDto.class) ||
                        innerClass.isAnnotationPresent(
                                CrudXResponseDto.class)) {

                    crudXDtoRegistry.registerDto(innerClass);

                    log.debug("✓ Registered inner class DTO: {} from controller: {}",
                            innerClass.getSimpleName(),
                            getClass().getSimpleName());
                }
            }
        } catch (Exception e) {
            log.debug("Could not scan inner classes for DTOs: {}", e.getMessage());
        }
    }

    private DatabaseType getDatabaseType() {
        DatabaseType databaseType;
        if (CrudXMongoEntity.class.isAssignableFrom(entityClass)) {
            databaseType = MONGODB;
        } else if (CrudXPostgreSQLEntity.class.isAssignableFrom(entityClass)) {
            databaseType = POSTGRESQL;
        } else if (CrudXMySQLEntity.class.isAssignableFrom(entityClass)) {
            databaseType = MYSQL;
        } else {
            throw new IllegalStateException("Unknown entity database type for class: " + entityClass.getSimpleName());
        }
        return databaseType;
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
                            entityClass.getSimpleName(),
                            idClass.getSimpleName());
                }
            }
        } catch (Exception e) {
            log.error("Error resolving generic types", e);
        }
    }

    // ===== CRUD ENDPOINTS =====

    @PostMapping
    public ResponseEntity<ApiResponse<?>> create(@RequestBody Object requestBody) {
        long startTime = System.currentTimeMillis();

        try {
            log.debug("Creating entity: {}", entityClass.getSimpleName());

            if (requestBody == null) {
                throw new IllegalArgumentException("Request body cannot be null");
            }

            // Map Request DTO to Entity
            T entity = mapRequestToEntity(requestBody, OperationType.CREATE);

            beforeCreate(entity);
            T created = crudService.create(entity);
            afterCreate(created);

            // Map Entity to Response DTO (or entity if no response DTO)
            Object response = mapEntityToResponse(created, OperationType.CREATE);

            long executionTime = System.currentTimeMillis() - startTime;
            log.info("✓ Entity created successfully with ID: {} | Time: {} ms",
                    created.getId(), executionTime);

            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(ApiResponse.success(response, "Entity created successfully",
                            HttpStatus.CREATED, executionTime));
        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("Create error: {} | Time: {} ms", e.getMessage(), executionTime, e);
            throw new RuntimeException("Failed to create entity: " + e.getMessage(), e);
        }
    }

    @PostMapping("/batch")
    public ResponseEntity<ApiResponse<?>> createBatch(
            @RequestBody List<Map<String, Object>> requestBodies,
            @RequestParam(required = false, defaultValue = "true") boolean skipDuplicates) {

        long startTime = System.currentTimeMillis();

        try {
            log.debug("Creating batch of {} entities (skipDuplicates: {})",
                    requestBodies.size(), skipDuplicates);

            if (requestBodies.isEmpty()) {
                throw new IllegalArgumentException("Request body list cannot be null or empty");
            }

            // Map DTOs/Maps to Entities
            List<T> entities = requestBodies.stream()
                    .map(dto -> mapRequestToEntity(dto, OperationType.BATCH_CREATE))
                    .collect(Collectors.toList());

            beforeCreateBatch(entities);

            final int MAX_BATCH_SIZE = 100000;
            if (entities.size() > MAX_BATCH_SIZE) {
                throw new IllegalArgumentException(
                        String.format("Batch size exceeds maximum limit of %d. Current size: %d",
                                MAX_BATCH_SIZE, entities.size())
                );
            }

            BatchResult<T> result;
            if (entities.size() > 100) {
                result = processBatchInChunks(entities, skipDuplicates);
            } else {
                result = crudService.createBatch(entities, skipDuplicates);
            }

            afterCreateBatch(result.getCreatedEntities());

            // Map to Response DTOs
            List<?> responseList = mapEntitiesToResponseList(result.getCreatedEntities(),
                    OperationType.BATCH_CREATE);
            BatchResult<?> responseResult = new BatchResult<>(responseList, result.getSkippedCount());
            responseResult.setSkippedReasons(result.getSkippedReasons());

            long executionTime = System.currentTimeMillis() - startTime;

            String message = result.hasSkipped()
                    ? String.format("Batch creation completed: %d created, %d skipped",
                    result.getCreatedEntities().size(), result.getSkippedCount())
                    : String.format("%d entities created successfully", result.getCreatedEntities().size());

            log.info("✓ {} | Time: {} ms", message, executionTime);

            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(ApiResponse.success(responseResult, message, HttpStatus.CREATED, executionTime));
        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("Batch create error: {} | Time: {} ms", e.getMessage(), executionTime, e);
            throw new RuntimeException("Failed to create batch: " + e.getMessage(), e);
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<?>> getById(@PathVariable ID id) {
        long startTime = System.currentTimeMillis();

        try {
            log.debug("Fetching entity by ID: {}", id);

            if (id == null) {
                throw new IllegalArgumentException("ID cannot be null");
            }

            T entity = crudService.findById(id);
            afterFindById(entity);

            if (entity == null) {
                throw new EntityNotFoundException(entityClass.getSimpleName(), id);
            }

            // Map to Response DTO (or entity if no response DTO)
            Object response = mapEntityToResponse(entity, OperationType.GET_BY_ID);

            long executionTime = System.currentTimeMillis() - startTime;
            log.info("✓ Entity found with ID: {} | Time: {} ms", id, executionTime);

            return ResponseEntity.ok(ApiResponse.success(response,
                    "Entity retrieved successfully", executionTime));
        } catch (EntityNotFoundException e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.warn("Entity not found with ID: {} | Time: {} ms", id, executionTime);
            throw e;
        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("Get by ID error for {}: {} | Time: {} ms",
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
            log.debug("Fetching all entities with sortBy: {}, direction: {}", sortBy, sortDirection);

            long totalCount = crudService.count();

            // Auto-switch to pagination for large datasets
            if (totalCount > LARGE_DATASET_THRESHOLD) {
                return getPagedAutomatic(sortBy, sortDirection, startTime, totalCount);
            }

            List<T> entities = fetchAllEntities(sortBy, sortDirection);

            if (entities == null || entities.isEmpty()) {
                throw new EntityNotFoundException("No " + entityClass.getSimpleName() + " entities found");
            }

            afterFindAll(entities);

            // Map to Response DTOs
            List<?> response = mapEntitiesToResponseList(entities, OperationType.GET_ALL);

            long executionTime = System.currentTimeMillis() - startTime;
            log.info("✓ Retrieved {} entities | Time: {} ms", response.size(), executionTime);

            return ResponseEntity.ok(ApiResponse.success(response,
                    String.format("Retrieved %d entities", response.size()),
                    executionTime));

        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("Get all error: {} | Time: {} ms", e.getMessage(), executionTime, e);
            throw new RuntimeException("Failed to retrieve entities: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    @GetMapping("/paged")
    public ResponseEntity<ApiResponse<PageResponse<?>>> getPaged(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false, defaultValue = "ASC") String sortDirection) {

        long startTime = System.currentTimeMillis();

        try {
            validatePageParams(page, size);

            Pageable pageable = buildPageable(page, size, sortBy, sortDirection);
            Page<T> springPage = crudService.findAll(pageable);

            // Map to Response DTOs
            List<?> responseContent = mapEntitiesToResponseList(springPage.getContent(),
                    OperationType.GET_PAGED);

            PageResponse<?> pageResponse = PageResponse.builder()
                    .content((List<Object>) responseContent)
                    .currentPage(springPage.getNumber())
                    .pageSize(springPage.getSize())
                    .totalElements(springPage.getTotalElements())
                    .totalPages(springPage.getTotalPages())
                    .first(springPage.isFirst())
                    .last(springPage.isLast())
                    .empty(springPage.isEmpty())
                    .build();

            afterFindPaged(PageResponse.from(springPage));

            if (pageResponse.getTotalElements() == 0) {
                throw new EntityNotFoundException("No " + entityClass.getSimpleName() + " entities found");
            }

            long executionTime = System.currentTimeMillis() - startTime;
            log.info("✓ Retrieved page {} with {} elements | Time: {} ms",
                    page, responseContent.size(), executionTime);

            String message = String.format("Retrieved page %d with %d elements (total: %d)",
                    page, responseContent.size(), pageResponse.getTotalElements());

            return ResponseEntity.ok(ApiResponse.success(pageResponse, message, executionTime));

        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("Get paged error: {} | Time: {} ms", e.getMessage(), executionTime, e);
            throw new RuntimeException("Failed to retrieve paged data: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    @PatchMapping("/{id}")
    public ResponseEntity<ApiResponse<?>> update(
            @PathVariable ID id,
            @RequestBody Object updateRequest) {

        long startTime = System.currentTimeMillis();

        try {
            log.debug("Updating entity with ID: {}", id);

            if (id == null) {
                throw new IllegalArgumentException("ID cannot be null");
            }

            Map<String, Object> updates;
            if (updateRequest instanceof Map) {
                updates = (Map<String, Object>) updateRequest;
            } else {
                // Convert Request DTO to Map
                updates = convertToMap(updateRequest);
            }

            T existingEntity = crudService.findById(id);
            beforeUpdate(id, updates, existingEntity);
            T oldEntity = cloneEntity(existingEntity);
            T updated = crudService.update(id, updates);
            afterUpdate(updated, oldEntity);

            // Map to Response DTO
            Object response = mapEntityToResponse(updated, OperationType.UPDATE);

            long executionTime = System.currentTimeMillis() - startTime;
            log.info("✓ Entity updated successfully with ID: {} | Time: {} ms", id, executionTime);

            return ResponseEntity.ok(ApiResponse.success(response, "Entity updated successfully", executionTime));
        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("Update error for ID {}: {} | Time: {} ms", id, e.getMessage(), executionTime, e);
            throw new RuntimeException("Failed to update entity: " + e.getMessage(), e);
        }
    }

    @GetMapping("/count")
    public ResponseEntity<ApiResponse<Long>> count() {
        long startTime = System.currentTimeMillis();

        try {
            log.debug("Counting all entities");
            long count = crudService.count();

            long executionTime = System.currentTimeMillis() - startTime;
            log.info("✓ Total entities count: {} | Time: {} ms", count, executionTime);

            return ResponseEntity.ok(ApiResponse.success(count,
                    String.format("Total count: %d", count),
                    executionTime));
        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("Count error: {} | Time: {} ms", e.getMessage(), executionTime, e);
            throw new RuntimeException("Failed to count entities: " + e.getMessage(), e);
        }
    }

    @GetMapping("/exists/{id}")
    public ResponseEntity<ApiResponse<Boolean>> exists(@PathVariable ID id) {
        long startTime = System.currentTimeMillis();

        try {
            log.debug("Checking existence of entity with ID: {}", id);

            if (id == null) {
                throw new IllegalArgumentException("ID cannot be null");
            }

            boolean exists = crudService.existsById(id);

            long executionTime = System.currentTimeMillis() - startTime;
            log.info("✓ Entity with ID {} exists: {} | Time: {} ms", id, exists, executionTime);

            return ResponseEntity.ok(ApiResponse.success(exists,
                    String.format("Entity %s", exists ? "exists" : "does not exist"),
                    executionTime));
        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("Exists check error for ID {}: {} | Time: {} ms",
                    id, e.getMessage(), executionTime, e);
            throw new RuntimeException("Failed to check entity existence: " + e.getMessage(), e);
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable ID id) {
        long startTime = System.currentTimeMillis();

        try {
            log.debug("Deleting entity with ID: {}", id);

            if (id == null) {
                throw new IllegalArgumentException("ID cannot be null");
            }

            T deletedEntity = crudService.delete(id);
            beforeDelete(id, deletedEntity);
            afterDelete(id, deletedEntity);

            long executionTime = System.currentTimeMillis() - startTime;
            log.info("✓ Entity deleted successfully with ID: {} | Time: {} ms", id, executionTime);

            return ResponseEntity.ok(ApiResponse.success(null, "Entity deleted successfully", executionTime));
        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("Delete error for ID {}: {} | Time: {} ms",
                    id, e.getMessage(), executionTime, e);
            throw new RuntimeException("Failed to delete entity: " + e.getMessage(), e);
        }
    }

    @DeleteMapping("/batch")
    public ResponseEntity<ApiResponse<BatchResult<ID>>> deleteBatch(@RequestBody List<ID> ids) {
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

            log.info("✓ {} | Time: {} ms", message, executionTime);

            return ResponseEntity.ok(ApiResponse.success(result, message, executionTime));

        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("Delete batch error: {} | Time: {} ms", e.getMessage(), executionTime, e);
            throw new RuntimeException("Failed to delete batch: " + e.getMessage(), e);
        }
    }

    @DeleteMapping("/batch/force")
    public ResponseEntity<ApiResponse<Void>> deleteBatchForce(@RequestBody List<ID> ids) {
        long startTime = System.currentTimeMillis();

        try {
            log.debug("Force deleting batch of {} entities (skip existence check)", ids.size());

            if (ids.isEmpty()) {
                throw new IllegalArgumentException("ID list cannot be null or empty");
            }

            if (ids.size() > LARGE_DATASET_THRESHOLD) {
                throw new IllegalArgumentException(
                        String.format("Cannot force delete more than %d records at once",
                                LARGE_DATASET_THRESHOLD));
            }

            beforeDeleteBatch(ids);

            int batchSize = 100;
            int totalDeleted = 0;
            List<ID> actuallyDeletedIds = new ArrayList<>();

            for (int i = 0; i < ids.size(); i += batchSize) {
                int end = Math.min(i + batchSize, ids.size());
                List<ID> batchIds = new ArrayList<>(ids.subList(i, end));

                crudService.deleteBatch(batchIds);
                totalDeleted += batchIds.size();
                actuallyDeletedIds.addAll(batchIds);

                batchIds.clear();
            }

            afterDeleteBatch(actuallyDeletedIds);

            long executionTime = System.currentTimeMillis() - startTime;
            log.info("✓ Force batch deletion completed: {} IDs processed | Time: {} ms",
                    totalDeleted, executionTime);

            return ResponseEntity.ok(ApiResponse.success(null,
                    String.format("%d IDs processed for deletion (existence not verified)", totalDeleted),
                    executionTime));

        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("Force delete batch error: {} | Time: {} ms", e.getMessage(), executionTime, e);
            throw new RuntimeException("Failed to force delete batch: " + e.getMessage(), e);
        }
    }

    // ===== DTO MAPPING HELPERS =====

    /**
     * Map REQUEST DTO/JSON to Entity
     * Logic: Use RequestDto if available, else convert directly to Entity
     */
    @SuppressWarnings("unchecked")
    private T mapRequestToEntity(Object requestBody, OperationType operation) {
        if (requestBody == null) {
            return null;
        }

        // Check if RequestDto is configured for this operation
        if (crudXDtoMapper != null && crudXDtoRegistry != null) {
            Class<?> requestDtoClass = crudXDtoRegistry.getRequestDtoClass(entityClass, operation);

            if (requestDtoClass != null) {
                // Convert Map/JSON to DTO first
                Object dto = requestBody;

                // If it's a Map (from JSON), convert to DTO
                if (requestBody instanceof Map) {
                    try {
                        dto = objectMapper.convertValue(requestBody, requestDtoClass);
                    } catch (IllegalArgumentException e) {
                        log.error("Failed to convert Map to DTO {}: {}",
                                requestDtoClass.getSimpleName(), e.getMessage());
                        throw new IllegalArgumentException(
                                String.format("Invalid request body format for %s: %s",
                                        requestDtoClass.getSimpleName(), e.getMessage())
                        );
                    }
                } else if (!requestDtoClass.isInstance(dto)) {
                    // If it's not the expected DTO type, try to convert
                    try {
                        dto = objectMapper.convertValue(requestBody, requestDtoClass);
                    } catch (IllegalArgumentException e) {
                        log.error("Failed to convert {} to DTO {}: {}",
                                requestBody.getClass().getSimpleName(),
                                requestDtoClass.getSimpleName(),
                                e.getMessage());
                        throw new IllegalArgumentException(
                                String.format("Invalid request body type. Expected: %s",
                                        requestDtoClass.getSimpleName())
                        );
                    }
                }

                // Map DTO → Entity
                T entity = crudXDtoMapper.toEntity(dto, entityClass);

                log.debug("Mapped REQUEST DTO {} → Entity {}",
                        requestDtoClass.getSimpleName(),
                        entityClass.getSimpleName());

                return entity;
            }
        }

        // No RequestDto configured - convert directly to Entity
        if (entityClass.isInstance(requestBody)) {
            return (T) requestBody;
        }

        // Convert Map/JSON → Entity
        try {
            T entity = objectMapper.convertValue(requestBody, entityClass);
            log.debug("Converted JSON → Entity {} (no RequestDto configured)",
                    entityClass.getSimpleName());
            return entity;
        } catch (IllegalArgumentException e) {
            log.error("Failed to convert request body to entity {}: {}",
                    entityClass.getSimpleName(), e.getMessage());
            throw new IllegalArgumentException(
                    String.format("Invalid request body format for %s. Error: %s",
                            entityClass.getSimpleName(), e.getMessage())
            );
        }
    }

    /**
     * Map Entity to RESPONSE DTO
     * Logic: Use ResponseDto if available, else return Entity
     */
    @SuppressWarnings("unchecked")
    private <R> R mapEntityToResponse(T entity, OperationType operation) {
        if (entity == null) {
            return null;
        }

        // Check if ResponseDto is configured for this operation
        if (crudXDtoMapper != null && crudXDtoRegistry != null) {
            Class<?> responseDtoClass = crudXDtoRegistry.getResponseDtoClass(entityClass, operation);

            if (responseDtoClass != null) {
                try {
                    R dto = (R) crudXDtoMapper.toDto(entity, entityClass, responseDtoClass);

                    log.debug("Mapped Entity {} → RESPONSE DTO {} for {}",
                            entityClass.getSimpleName(),
                            responseDtoClass.getSimpleName(),
                            operation);

                    return dto;
                } catch (Exception e) {
                    log.error("Failed to map to RESPONSE DTO {}: {}. Returning entity.",
                            responseDtoClass.getSimpleName(), e.getMessage());
                }
            }
        }

        // No ResponseDto configured - return full entity
        log.debug("No RESPONSE DTO configured for {} on {}. Returning entity.",
                operation, entityClass.getSimpleName());
        return (R) entity;
    }

    /**
     * Map List of Entities to Response DTOs
     */
    @SuppressWarnings("unchecked")
    private <R> List<R> mapEntitiesToResponseList(List<T> entities, OperationType operation) {
        if (entities == null || entities.isEmpty()) {
            return (List<R>) entities;
        }

        // Check if ResponseDto is configured
        if (crudXDtoMapper != null && crudXDtoRegistry != null) {
            Class<?> responseDtoClass = crudXDtoRegistry.getResponseDtoClass(entityClass, operation);

            if (responseDtoClass != null) {
                try {
                    List<R> dtos = (List<R>) crudXDtoMapper.toDtos(entities, entityClass, responseDtoClass);

                    log.debug("Batch mapped {} entities → {} DTOs for {}",
                            entities.size(),
                            responseDtoClass.getSimpleName(),
                            operation);

                    return dtos;
                } catch (Exception e) {
                    log.error("Failed to batch map to RESPONSE DTO {}: {}. Returning entities.",
                            responseDtoClass.getSimpleName(), e.getMessage());
                }
            }
        }

        // No ResponseDto - return entities
        log.debug("No RESPONSE DTO configured for {}. Returning entities.", operation);
        return (List<R>) entities;
    }

    // ===== UTILITY METHODS =====

    private BatchResult<T> processBatchInChunks(List<T> entities, boolean skipDuplicates) {
        final int OPTIMAL_CHUNK_SIZE = 500;
        log.info("Processing batch of {} entities in chunks of {}",
                entities.size(), OPTIMAL_CHUNK_SIZE);

        BatchResult<T> combinedResult = new BatchResult<>();
        List<T> allCreated = new ArrayList<>();
        int totalSkipped = 0;
        List<String> allSkippedReasons = new ArrayList<>();

        int totalChunks = (entities.size() + OPTIMAL_CHUNK_SIZE - 1) / OPTIMAL_CHUNK_SIZE;
        int chunkNumber = 0;

        for (int i = 0; i < entities.size(); i += OPTIMAL_CHUNK_SIZE) {
            chunkNumber++;
            int end = Math.min(i + OPTIMAL_CHUNK_SIZE, entities.size());
            List<T> chunk = new ArrayList<>(entities.subList(i, end));

            try {
                BatchResult<T> chunkResult = crudService.createBatch(chunk, skipDuplicates);
                allCreated.addAll(chunkResult.getCreatedEntities());
                totalSkipped += chunkResult.getSkippedCount();

                if (chunkResult.getSkippedReasons() != null) {
                    allSkippedReasons.addAll(chunkResult.getSkippedReasons());
                }

                log.debug("Chunk {}/{} completed: {} created, {} skipped",
                        chunkNumber, totalChunks,
                        chunkResult.getCreatedEntities().size(),
                        chunkResult.getSkippedCount());

            } catch (Exception chunkError) {
                if (!skipDuplicates) throw chunkError;

                totalSkipped += (end - i);
                allSkippedReasons.add(String.format("Chunk %d failed: %s",
                        chunkNumber, chunkError.getMessage()));
            } finally {
                chunk.clear();
            }
        }

        combinedResult.setCreatedEntities(allCreated);
        combinedResult.setSkippedCount(totalSkipped);
        combinedResult.setSkippedReasons(allSkippedReasons);

        return combinedResult;
    }

    private ResponseEntity<ApiResponse<?>> getPagedAutomatic(
            String sortBy, String sortDirection, long startTime, long totalCount) {

        log.warn("Large dataset detected ({} records). Auto-switching to paginated response", totalCount);

        Pageable pageable = buildPageable(0, DEFAULT_PAGE_SIZE, sortBy, sortDirection);
        Page<T> springPage = crudService.findAll(pageable);

        List<Object> responseContent = mapEntitiesToResponseList(springPage.getContent(),
                OperationType.GET_ALL);

        PageResponse<?> pageResponse = PageResponse.builder()
                .content(responseContent)
                .currentPage(springPage.getNumber())
                .pageSize(springPage.getSize())
                .totalElements(springPage.getTotalElements())
                .totalPages(springPage.getTotalPages())
                .first(springPage.isFirst())
                .last(springPage.isLast())
                .empty(springPage.isEmpty())
                .build();

        afterFindPaged(PageResponse.from(springPage));

        long executionTime = System.currentTimeMillis() - startTime;
        log.info("✓ Retrieved first page of {} elements (total: {}) | Time: {} ms",
                responseContent.size(), totalCount, executionTime);

        return ResponseEntity.ok(ApiResponse.success(pageResponse,
                String.format("Large dataset detected (%d total records). " +
                                "Returning first %d records. Use /paged endpoint with page parameter for more data.",
                        totalCount, responseContent.size()),
                executionTime));
    }

    private List<T> fetchAllEntities(String sortBy, String sortDirection) {
        if (sortBy != null) {
            Sort.Direction direction = Sort.Direction.fromString(sortDirection);
            return crudService.findAll(Sort.by(direction, sortBy));
        } else {
            return crudService.findAll();
        }
    }

    private void validatePageParams(int page, int size) {
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

    private Pageable buildPageable(int page, int size, String sortBy, String sortDirection) {
        if (sortBy != null) {
            Sort.Direction direction = Sort.Direction.fromString(sortDirection);
            return PageRequest.of(page, size, Sort.by(direction, sortBy));
        } else {
            return PageRequest.of(page, size);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> convertToMap(Object obj) {
        if (obj == null) {
            return new HashMap<>();
        }
        if (obj instanceof Map) {
            return (Map<String, Object>) obj;
        }
        try {
            return objectMapper.convertValue(obj, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            log.error("Failed to convert object to Map: {}", e.getMessage());
            return new HashMap<>();
        }
    }

    private T cloneEntity(T entity) {
        try {
            return (T) BeanUtils.instantiateClass(entityClass);
        } catch (Exception e) {
            log.warn("Could not clone entity for comparison", e);
            return null;
        }
    }

    protected Class<T> getEntityClass() {
        return entityClass;
    }

    protected Class<ID> getIdClass() {
        return idClass;
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
     * <p>
     * NOTE: The entity is already deleted from the database at this point.
     * This hook is for post-deletion operations like logging, cache invalidation,
     * or triggering external events. If you need to PREVENT deletion, consider
     * using a custom validation endpoint before calling delete.
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
     * <p>
     * NOTE: The deletedEntity parameter contains the entity as it existed
     * before deletion, giving you access to all fields for cleanup operations.
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