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

import io.github.sachinnimbal.crudx.core.enums.DatabaseType;
import io.github.sachinnimbal.crudx.core.enums.Direction;
import io.github.sachinnimbal.crudx.core.enums.OperationType;
import io.github.sachinnimbal.crudx.core.exception.DuplicateEntityException;
import io.github.sachinnimbal.crudx.core.exception.EntityNotFoundException;
import io.github.sachinnimbal.crudx.core.model.CrudXBaseEntity;
import io.github.sachinnimbal.crudx.core.model.CrudXMongoEntity;
import io.github.sachinnimbal.crudx.core.model.CrudXMySQLEntity;
import io.github.sachinnimbal.crudx.core.model.CrudXPostgreSQLEntity;
import io.github.sachinnimbal.crudx.core.response.ApiResponse;
import io.github.sachinnimbal.crudx.core.response.BatchResult;
import io.github.sachinnimbal.crudx.core.response.PageResponse;
import io.github.sachinnimbal.crudx.dto.mapper.DtoMapper;
import io.github.sachinnimbal.crudx.dto.metadata.DtoMetadata;
import io.github.sachinnimbal.crudx.dto.registry.DtoRegistry;
import io.github.sachinnimbal.crudx.dto.validation.DtoValidator;
import io.github.sachinnimbal.crudx.dto.validation.UniqueCheckResult;
import io.github.sachinnimbal.crudx.dto.validation.UniqueConstraintChecker;
import io.github.sachinnimbal.crudx.dto.validation.ValidationResult;
import io.github.sachinnimbal.crudx.service.CrudXService;
import jakarta.annotation.PostConstruct;
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
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static io.github.sachinnimbal.crudx.core.enums.DatabaseType.*;

/**
 * Base REST controller providing zero-boilerplate CRUD operations.
 *
 * <p><b>Usage Examples:</b></p>
 *
 * <pre>
 * // Example 1: Simple controller with all default endpoints
 * {@literal @}RestController
 * {@literal @}RequestMapping("/api/users")
 * public class UserController extends CrudXController&lt;User, String&gt; {
 *     // No additional code needed - all CRUD endpoints are auto-generated
 * }
 *
 * // Example 2: Controller with lifecycle hooks
 * {@literal @}RestController
 * {@literal @}RequestMapping("/api/products")
 * public class ProductController extends CrudXController&lt;Product, Long&gt; {
 *
 *     {@literal @}Override
 *     protected void beforeCreate(Product product) {
 *         // Custom logic before creating product
 *         product.setSku(generateSku());
 *     }
 *
 *     {@literal @}Override
 *     protected void afterCreate(Product product) {
 *         // Send notification after product creation
 *         notificationService.sendProductCreated(product);
 *     }
 *
 *     {@literal @}Override
 *     protected void beforeDelete(Long id, Product product) {
 *         // Check if product can be deleted
 *         if (product.hasActiveOrders()) {
 *             throw new IllegalStateException("Cannot delete product with active orders");
 *         }
 *     }
 *
 *     {@literal @}Override
 *     protected void afterDelete(Long id, Product deletedProduct) {
 *         // Clean up related data after deletion
 *         cacheService.invalidateProduct(id);
 *         searchService.removeFromIndex(id);
 *     }
 * }
 *
 * // Example 3: Controller with custom endpoints
 * {@literal @}RestController
 * {@literal @}RequestMapping("/api/orders")
 * public class OrderController extends CrudXController&lt;Order, String&gt; {
 *
 *     {@literal @}Autowired
 *     private PaymentService paymentService;
 *
 *     // Custom endpoint in addition to CRUD operations
 *     {@literal @}PostMapping("/{id}/pay")
 *     public ResponseEntity&lt;ApiResponse&lt;Order&gt;&gt; processPayment(
 *             {@literal @}PathVariable String id,
 *             {@literal @}RequestBody PaymentRequest payment) {
 *
 *         Order order = crudService.findById(id);
 *         paymentService.process(order, payment);
 *
 *         return ResponseEntity.ok(ApiResponse.success(order, "Payment processed"));
 *     }
 * }
 * </pre>
 *
 * <p><b>Available Lifecycle Hooks:</b></p>
 * <ul>
 *   <li>{@code beforeCreate(T entity)} - Called before entity creation</li>
 *   <li>{@code afterCreate(T entity)} - Called after entity creation</li>
 *   <li>{@code beforeCreateBatch(List<T> entities)} - Called before batch creation</li>
 *   <li>{@code afterCreateBatch(List<T> entities)} - Called after batch creation</li>
 *   <li>{@code beforeUpdate(ID id, Map updates, T existing)} - Called before update</li>
 *   <li>{@code afterUpdate(T updated, T old)} - Called after update</li>
 *   <li>{@code beforeDelete(ID id, T entity)} - Called before deletion</li>
 *   <li>{@code afterDelete(ID id, T deleted)} - Called after deletion</li>
 *   <li>{@code beforeDeleteBatch(List<ID> ids)} - Called before batch deletion</li>
 *   <li>{@code afterDeleteBatch(List<ID> ids)} - Called after batch deletion</li>
 *   <li>{@code afterFindById(T entity)} - Called after finding by ID</li>
 *   <li>{@code afterFindAll(List<T> entities)} - Called after finding all</li>
 *   <li>{@code afterFindPaged(PageResponse<T> pageResponse)} - Called after paged find</li>
 * </ul>
 *
 * <p><b>Auto-generated Endpoints:</b></p>
 * <ul>
 *   <li>POST / - Create single entity</li>
 *   <li>POST /batch - Create multiple entities</li>
 *   <li>GET /{id} - Get entity by ID</li>
 *   <li>GET / - Get all entities (with sorting)</li>
 *   <li>GET /paged - Get paginated entities</li>
 *   <li>PATCH /{id} - Update entity</li>
 *   <li>DELETE /{id} - Delete entity</li>
 *   <li>DELETE /batch - Delete multiple entities</li>
 *   <li>DELETE /batch/force - Force delete without existence check</li>
 *   <li>GET /count - Count all entities</li>
 *   <li>GET /exists/{id} - Check if entity exists</li>
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
    private DtoRegistry dtoRegistry;

    @Autowired(required = false)
    private DtoMapper dtoMapper;

    @Autowired(required = false)
    private DtoValidator dtoValidator;

    @Autowired(required = false)
    private UniqueConstraintChecker uniqueChecker;

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

    @PostMapping
    public ResponseEntity<ApiResponse<?>> create(@RequestBody Object requestBody) {
        long startTime = System.currentTimeMillis();

        try {
            log.debug("Creating entity: {}", entityClass.getSimpleName());

            if (requestBody == null) {
                throw new IllegalArgumentException("Request body cannot be null");
            }

            // Lazy-load DTO if needed
            if (hasDtoConfigured(OperationType.CREATE, Direction.REQUEST)) {
                DtoMetadata metadata = dtoRegistry.findDto(entityClass, OperationType.CREATE, Direction.REQUEST);
                dtoRegistry.registerDto(metadata.getDtoClass()); // Ensure registered
            }

            // Map DTO to Entity (or use entity directly if no DTO)
            T entity = mapRequestToEntity(requestBody, OperationType.CREATE);

            beforeCreate(entity);
            T created = crudService.create(entity);
            afterCreate(created);

            // Map Entity to Response DTO (or return entity if no DTO)
            Object response = mapEntityToResponse(created, OperationType.CREATE);

            long executionTime = System.currentTimeMillis() - startTime;

            log.info("Entity created successfully with ID: {} | Time taken: {} ms",
                    created.getId(), executionTime);

            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(ApiResponse.success(response, "Entity created successfully",
                            HttpStatus.CREATED, executionTime));
        } catch (IllegalArgumentException e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("Invalid entity data: {} | Time taken: {} ms", e.getMessage(), executionTime);
            throw e;
        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("Error creating entity: {} | Time taken: {} ms", e.getMessage(), executionTime, e);
            throw new RuntimeException("Failed to create entity: " + e.getMessage(), e);
        }
    }

    @PostMapping("/batch")
    public ResponseEntity<ApiResponse<?>> createBatch(
            @RequestBody List<?> requestBodies,
            @RequestParam(required = false, defaultValue = "true") boolean skipDuplicates) {

        long startTime = System.currentTimeMillis();

        try {
            log.debug("Creating batch of {} entities (skipDuplicates: {})",
                    requestBodies.size(), skipDuplicates);

            if (requestBodies.isEmpty()) {
                throw new IllegalArgumentException("Request body list cannot be null or empty");
            }

            // Lazy-load DTO if needed
            if (hasDtoConfigured(OperationType.BATCH_CREATE, Direction.REQUEST)) {
                DtoMetadata metadata = dtoRegistry.findDto(entityClass, OperationType.BATCH_CREATE, Direction.REQUEST);
                dtoRegistry.registerDto(metadata.getDtoClass());
            }

            // Map DTOs to Entities
            List<T> entities = new ArrayList<>(requestBodies.size());
            for (Object requestBody : requestBodies) {
                T entity = mapRequestToEntity(requestBody, OperationType.BATCH_CREATE);
                entities.add(entity);
            }

            beforeCreateBatch(entities);

            final int MAX_BATCH_SIZE = 100000;
            if (entities.size() > MAX_BATCH_SIZE) {
                throw new IllegalArgumentException(
                        String.format("Batch size exceeds maximum limit of %d. Current size: %d",
                                MAX_BATCH_SIZE, entities.size())
                );
            }

            // Process in optimal chunks
            final int OPTIMAL_CHUNK_SIZE = 500;

            if (entities.size() > 100) {
                log.info("Processing batch of {} entities in chunks of {} (Memory-optimized mode)",
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

                    long chunkStart = System.currentTimeMillis();

                    try {
                        BatchResult<T> chunkResult = crudService.createBatch(chunk, skipDuplicates);
                        allCreated.addAll(chunkResult.getCreatedEntities());
                        totalSkipped += chunkResult.getSkippedCount();

                        if (chunkResult.getSkippedReasons() != null) {
                            allSkippedReasons.addAll(chunkResult.getSkippedReasons());
                        }

                        long chunkTime = System.currentTimeMillis() - chunkStart;
                        log.debug("Chunk {}/{} completed: {} created, {} skipped | Time: {} ms",
                                chunkNumber, totalChunks,
                                chunkResult.getCreatedEntities().size(),
                                chunkResult.getSkippedCount(),
                                chunkTime);

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

                afterCreateBatch(allCreated);

                // Map to Response DTOs if configured
                List<?> responseList = mapEntitiesToResponse(allCreated, OperationType.BATCH_CREATE);
                BatchResult<?> responseResult = new BatchResult<>(responseList, totalSkipped);
                responseResult.setSkippedReasons(allSkippedReasons);

                long executionTime = System.currentTimeMillis() - startTime;
                double recordsPerSecond = (allCreated.size() * 1000.0) / executionTime;

                String message = String.format(
                        "Batch creation completed: %d created, %d skipped | Performance: %.0f records/sec",
                        allCreated.size(), totalSkipped, recordsPerSecond);

                return ResponseEntity.status(HttpStatus.CREATED)
                        .body(ApiResponse.success(responseResult, message, HttpStatus.CREATED, executionTime));
            }

            // Small batch - process directly
            BatchResult<T> result = crudService.createBatch(entities, skipDuplicates);
            afterCreateBatch(result.getCreatedEntities());

            // Map to Response DTOs
            List<?> responseList = mapEntitiesToResponse(result.getCreatedEntities(), OperationType.BATCH_CREATE);
            BatchResult<?> responseResult = new BatchResult<>(responseList, result.getSkippedCount());
            responseResult.setSkippedReasons(result.getSkippedReasons());

            long executionTime = System.currentTimeMillis() - startTime;

            String message = result.hasSkipped()
                    ? String.format("Batch creation completed: %d created, %d skipped",
                    result.getCreatedEntities().size(), result.getSkippedCount())
                    : String.format("%d entities created successfully", result.getCreatedEntities().size());

            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(ApiResponse.success(responseResult, message, HttpStatus.CREATED, executionTime));
        } catch (IllegalArgumentException e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("Invalid batch data: {} | Time taken: {} ms", e.getMessage(), executionTime);
            throw e;
        } catch (DuplicateEntityException e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("Duplicate entity in batch (skipDuplicates=false): {} | Time taken: {} ms",
                    e.getMessage(), executionTime);
            throw e;
        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("Error creating batch: {} | Time taken: {} ms", e.getMessage(), executionTime, e);
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

            // Lazy-load response DTO if needed
            if (hasDtoConfigured(OperationType.GET_BY_ID, Direction.RESPONSE)) {
                DtoMetadata metadata = dtoRegistry.findDto(entityClass, OperationType.GET_BY_ID, Direction.RESPONSE);
                dtoRegistry.registerDto(metadata.getDtoClass());
            }

            T entity = crudService.findById(id);
            afterFindById(entity);

            if (entity == null) {
                throw new EntityNotFoundException(entityClass.getSimpleName(), id);
            }

            Object response = mapEntityToResponse(entity, OperationType.GET_BY_ID);
            long executionTime = System.currentTimeMillis() - startTime;

            log.info("Entity found with ID: {} | Time taken: {} ms", id, executionTime);

            return ResponseEntity.ok(ApiResponse.success(response, "Entity retrieved successfully", executionTime));
        } catch (EntityNotFoundException e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.warn("Entity not found with ID: {} | Time taken: {} ms", id, executionTime);
            throw e;
        } catch (IllegalArgumentException e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("Invalid ID parameter: {} | Time taken: {} ms", e.getMessage(), executionTime);
            throw e;
        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("Error fetching entity by ID {}: {} | Time taken: {} ms", id, e.getMessage(), executionTime, e);
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

            // Lazy-load response DTO
            if (hasDtoConfigured(OperationType.GET_ALL, Direction.RESPONSE)) {
                DtoMetadata metadata = dtoRegistry.findDto(entityClass, OperationType.GET_ALL, Direction.RESPONSE);
                dtoRegistry.registerDto(metadata.getDtoClass());
            }

            // Check total count first
            long totalCount = crudService.count();

            // If dataset is large, automatically return paginated response with 50 records
            if (totalCount > LARGE_DATASET_THRESHOLD) {
                log.warn("Large dataset detected ({} records). Auto-switching to paginated response", totalCount);

                // Build pageable with 50 records for large datasets
                Pageable pageable;
                if (sortBy != null) {
                    try {
                        Sort.Direction direction = Sort.Direction.fromString(sortDirection);
                        pageable = PageRequest.of(0, DEFAULT_PAGE_SIZE, Sort.by(direction, sortBy));
                    } catch (IllegalArgumentException e) {
                        throw new IllegalArgumentException("Invalid sort direction: " + sortDirection + ". Must be ASC or DESC");
                    }
                } else {
                    pageable = PageRequest.of(0, 50);
                }

                Page<T> springPage = crudService.findAll(pageable);
                List<?> responseContent = mapEntitiesToResponse(springPage.getContent(), OperationType.GET_ALL);

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

                long executionTime = System.currentTimeMillis() - startTime;

                log.info("Retrieved first page of {} elements (total: {}) | Time taken: {} ms",
                        responseContent.size(), totalCount, executionTime);

                return ResponseEntity.ok(ApiResponse.success(pageResponse,
                        String.format("Large dataset detected (%d total records). " +
                                        "Returning first %d records. Use /paged endpoint with page parameter for more data.",
                                totalCount, responseContent.size()),
                        executionTime));
            }

            // For small datasets, return all data
            List<T> entities;
            if (sortBy != null) {
                try {
                    Sort.Direction direction = Sort.Direction.fromString(sortDirection);
                    entities = crudService.findAll(Sort.by(direction, sortBy));
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("Invalid sort direction: " + sortDirection + ". Must be ASC or DESC");
                }
            } else {
                entities = crudService.findAll();
            }

            if (entities == null || entities.isEmpty()) {
                log.info("No entities found");
                throw new EntityNotFoundException("No " + entityClass.getSimpleName() + " entities found");
            }

            afterFindAll(entities);

            List<?> response = mapEntitiesToResponse(entities, OperationType.GET_ALL);

            long executionTime = System.currentTimeMillis() - startTime;

            log.info("Retrieved {} entities | Time taken: {} ms", response.size(), executionTime);

            return ResponseEntity.ok(ApiResponse.success(response,
                    String.format("Retrieved %d entities", response.size()),
                    executionTime));

        } catch (EntityNotFoundException e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.warn("No entities found | Time taken: {} ms", executionTime);
            throw e;
        } catch (IllegalArgumentException e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("Invalid parameters: {} | Time taken: {} ms", e.getMessage(), executionTime);
            throw e;
        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("Error fetching all entities: {} | Time taken: {} ms", e.getMessage(), executionTime, e);
            throw new RuntimeException("Failed to retrieve entities: " + e.getMessage(), e);
        }
    }

    @GetMapping("/paged")
    public ResponseEntity<ApiResponse<PageResponse<?>>> getPaged(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false, defaultValue = "ASC") String sortDirection) {

        long startTime = System.currentTimeMillis();

        try {
            log.debug("Fetching paged entities: page={}, size={}, sortBy={}, direction={}",
                    page, size, sortBy, sortDirection);

            if (page < 0) {
                throw new IllegalArgumentException("Page number cannot be negative");
            }

            if (size <= 0) {
                throw new IllegalArgumentException("Page size must be greater than 0");
            }

            if (size > MAX_PAGE_SIZE) {
                throw new IllegalArgumentException("Page size cannot exceed " + MAX_PAGE_SIZE);
            }

            // Lazy-load response DTO
            if (hasDtoConfigured(OperationType.GET_PAGED, Direction.RESPONSE)) {
                DtoMetadata metadata = dtoRegistry.findDto(entityClass, OperationType.GET_PAGED, Direction.RESPONSE);
                dtoRegistry.registerDto(metadata.getDtoClass());
            }

            Pageable pageable;
            if (sortBy != null) {
                try {
                    Sort.Direction direction = Sort.Direction.fromString(sortDirection);
                    pageable = PageRequest.of(page, size, Sort.by(direction, sortBy));
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("Invalid sort direction: " + sortDirection + ". Must be ASC or DESC");
                }
            } else {
                pageable = PageRequest.of(page, size);
            }

            Page<T> springPage = crudService.findAll(pageable);
            List<?> responseContent = mapEntitiesToResponse(springPage.getContent(), OperationType.GET_PAGED);

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
                log.info("No entities found for page request");
                throw new EntityNotFoundException("No " + entityClass.getSimpleName() + " entities found");
            }

            long executionTime = System.currentTimeMillis() - startTime;

            log.info("Found page of {} {} entities (total: {}) | Time taken: {} ms",
                    responseContent.size(),
                    entityClass.getSimpleName(),
                    pageResponse.getTotalElements(),
                    executionTime);

            String message = String.format("Retrieved page %d with %d elements (total: %d)",
                    page, responseContent.size(), pageResponse.getTotalElements());

            return ResponseEntity.ok(ApiResponse.success(pageResponse, message, executionTime));

        } catch (EntityNotFoundException e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.warn("No entities found | Time taken: {} ms", executionTime);
            throw e;
        } catch (IllegalArgumentException e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("Invalid pagination parameters: {} | Time taken: {} ms", e.getMessage(), executionTime);
            throw e;
        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("Error fetching paged entities: {} | Time taken: {} ms", e.getMessage(), executionTime, e);
            throw new RuntimeException("Failed to retrieve paged data: " + e.getMessage(), e);
        }
    }

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

            // Lazy-load DTO if needed
            if (hasDtoConfigured(OperationType.UPDATE, Direction.REQUEST)) {
                DtoMetadata metadata = dtoRegistry.findDto(entityClass, OperationType.UPDATE, Direction.REQUEST);
                dtoRegistry.registerDto(metadata.getDtoClass());

                // Check for immutable fields in DTO
                if (updateRequest instanceof Map) {
                    Map<String, Object> updateMap = (Map<String, Object>) updateRequest;
                    for (String immutableField : metadata.getImmutableFields()) {
                        if (updateMap.containsKey(immutableField)) {
                            throw new IllegalArgumentException(
                                    "Cannot update immutable field: " + immutableField
                            );
                        }
                    }
                }
            }

            Map<String, Object> updates;
            if (updateRequest instanceof Map) {
                updates = (Map<String, Object>) updateRequest;
            } else {
                // Convert DTO to Map
                updates = convertDtoToMap(updateRequest);
            }

            T existingEntity = crudService.findById(id);
            beforeUpdate(id, updates, existingEntity);
            T oldEntity = cloneEntity(existingEntity);
            T updated = crudService.update(id, updates);
            afterUpdate(updated, oldEntity);

            Object response = mapEntityToResponse(updated, OperationType.UPDATE);

            long executionTime = System.currentTimeMillis() - startTime;

            return ResponseEntity.ok(ApiResponse.success(response, "Entity updated successfully", executionTime));
        } catch (EntityNotFoundException e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.warn("Entity not found for update with ID: {} | Time taken: {} ms", id, executionTime);
            throw e;
        } catch (IllegalArgumentException e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("Invalid update data: {} | Time taken: {} ms", e.getMessage(), executionTime);
            throw e;
        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("Error updating entity with ID {}: {} | Time taken: {} ms", id, e.getMessage(), executionTime, e);
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

        try {
            log.debug("Checking existence of entity with ID: {}", id);

            if (id == null) {
                throw new IllegalArgumentException("ID cannot be null");
            }

            boolean exists = crudService.existsById(id);

            long executionTime = System.currentTimeMillis() - startTime;

            log.info("Entity with ID {} exists: {} | Time taken: {} ms", id, exists, executionTime);

            return ResponseEntity.ok(ApiResponse.success(exists,
                    String.format("Entity %s", exists ? "exists" : "does not exist"),
                    executionTime));
        } catch (IllegalArgumentException e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("Invalid ID parameter: {} | Time taken: {} ms", e.getMessage(), executionTime);
            throw e;
        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("Error checking entity existence with ID {}: {} | Time taken: {} ms", id, e.getMessage(), executionTime, e);
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

            // OPTIMIZED: Service returns entity before deleting (single DB hit)
            T deletedEntity = crudService.delete(id);
            // Call lifecycle hooks with the already-fetched entity
            beforeDelete(id, deletedEntity);
            // Note: Entity is already deleted by service at this point
            afterDelete(id, deletedEntity);

            long executionTime = System.currentTimeMillis() - startTime;

            log.info("Entity deleted successfully with ID: {} | Time taken: {} ms", id, executionTime);

            return ResponseEntity.ok(ApiResponse.success(null, "Entity deleted successfully", executionTime));
        } catch (EntityNotFoundException e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.warn("Entity not found for deletion with ID: {} | Time taken: {} ms", id, executionTime);
            throw e;
        } catch (IllegalArgumentException e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("Invalid ID parameter: {} | Time taken: {} ms", e.getMessage(), executionTime);
            throw e;
        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("Error deleting entity with ID {}: {} | Time taken: {} ms", id, e.getMessage(), executionTime, e);
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

            // Convert to ID-based result for response
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

        } catch (IllegalArgumentException e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("Invalid batch delete data: {} | Time taken: {} ms", e.getMessage(), executionTime);
            throw e;
        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("Error deleting batch: {} | Time taken: {} ms", e.getMessage(), executionTime, e);
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
                        String.format("Cannot force delete more than %d records at once. " +
                                        "Current request: %d IDs. Use regular /batch endpoint or multiple requests.",
                                LARGE_DATASET_THRESHOLD, ids.size()));
            }

            // Call beforeDeleteBatch lifecycle hook
            beforeDeleteBatch(ids);

            // Process in smaller batches to minimize memory footprint
            int batchSize = 100;
            int totalDeleted = 0;
            List<ID> actuallyDeletedIds = new ArrayList<>();

            for (int i = 0; i < ids.size(); i += batchSize) {
                int end = Math.min(i + batchSize, ids.size());
                List<ID> batchIds = new ArrayList<>(ids.subList(i, end));

                crudService.deleteBatch(batchIds);
                totalDeleted += batchIds.size();
                actuallyDeletedIds.addAll(batchIds);

                // Clear batch to allow GC
                batchIds.clear();

                log.debug("Force deleted batch {}/{} entities (memory optimized)", totalDeleted, ids.size());
            }

            // Call afterDeleteBatch with all processed IDs
            afterDeleteBatch(actuallyDeletedIds);

            long executionTime = System.currentTimeMillis() - startTime;

            log.info("Force batch deletion completed: {} IDs processed (may include non-existent IDs) | Time taken: {} ms",
                    totalDeleted, executionTime);

            return ResponseEntity.ok(ApiResponse.success(null,
                    String.format("%d IDs processed for deletion (existence not verified)", totalDeleted),
                    executionTime));

        } catch (IllegalArgumentException e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("Invalid force delete data: {} | Time taken: {} ms", e.getMessage(), executionTime);
            throw e;
        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("Error force deleting batch: {} | Time taken: {} ms", e.getMessage(), executionTime, e);
            throw new RuntimeException("Failed to force delete batch: " + e.getMessage(), e);
        }
    }

    // ===== Utility Methods =====

    /**
     * Helper: Convert DTO object to Map for update operations
     */
    private Map<String, Object> convertDtoToMap(Object dto) {
        Map<String, Object> map = new HashMap<>();
        try {
            for (Field field : dto.getClass().getDeclaredFields()) {
                field.setAccessible(true);
                Object value = field.get(dto);
                if (value != null) {
                    map.put(field.getName(), value);
                }
            }
        } catch (Exception e) {
            log.error("Failed to convert DTO to Map", e);
        }
        return map;
    }

    protected Class<T> getEntityClass() {
        return entityClass;
    }

    protected Class<ID> getIdClass() {
        return idClass;
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

    /**
     * Detect if DTO is configured for this operation
     */
    private boolean hasDtoConfigured(OperationType operation, Direction direction) {
        return dtoRegistry != null &&
                dtoMapper != null &&
                dtoRegistry.findDto(entityClass, operation, direction) != null;
    }

    /**
     * Map entity to response DTO if configured, otherwise return entity
     */
    @SuppressWarnings("unchecked")
    private <R> R mapEntityToResponse(T entity, OperationType operation) {
        if (!hasDtoConfigured(operation, Direction.RESPONSE)) {
            return (R) entity; // No DTO, return entity
        }

        DtoMetadata metadata = dtoRegistry.findDto(entityClass, operation, Direction.RESPONSE);
        return (R) dtoMapper.toDto(entity, metadata.getDtoClass(), operation);
    }

    /**
     * Map entity list to response DTOs if configured
     */
    @SuppressWarnings("unchecked")
    private <R> List<R> mapEntitiesToResponse(List<T> entities, OperationType operation) {
        if (!hasDtoConfigured(operation, Direction.RESPONSE)) {
            return (List<R>) entities; // No DTO, return entities
        }

        DtoMetadata metadata = dtoRegistry.findDto(entityClass, operation, Direction.RESPONSE);
        return (List<R>) dtoMapper.toDtos(entities, metadata.getDtoClass(), operation);
    }

    /**
     * Enhanced mapRequestToEntity with validation
     */
    @SuppressWarnings("unchecked")
    private <D> T mapRequestToEntity(D requestBody, OperationType operation) {
        if (!hasDtoConfigured(operation, Direction.REQUEST)) {
            return (T) requestBody; // No DTO, use entity directly
        }

        // Step 1: Validate inherited constraints
        if (dtoValidator != null) {
            ValidationResult validationResult = dtoValidator.validate(
                    requestBody, entityClass, operation);

            if (!validationResult.isValid()) {
                throw new IllegalArgumentException(validationResult.getErrorMessage());
            }
        }

        // Step 2: Check unique constraints (pre-optimization)
        if (uniqueChecker != null && (operation == OperationType.CREATE ||
                operation == OperationType.BATCH_CREATE)) {
            UniqueCheckResult uniqueResult = uniqueChecker.checkUniqueConstraints(
                    requestBody, entityClass, operation);

            if (!uniqueResult.isUnique()) {
                throw new io.github.sachinnimbal.crudx.core.exception.DuplicateEntityException(
                        uniqueResult.getMessage());
            }
        }

        // Step 3: Map DTO to Entity
        return dtoMapper.toEntity(requestBody, entityClass, operation);
    }
}