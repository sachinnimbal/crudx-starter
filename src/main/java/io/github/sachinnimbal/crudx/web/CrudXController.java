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

import io.github.sachinnimbal.crudx.core.exception.DuplicateEntityException;
import io.github.sachinnimbal.crudx.core.exception.EntityNotFoundException;
import io.github.sachinnimbal.crudx.core.model.CrudXBaseEntity;
import io.github.sachinnimbal.crudx.core.response.ApiResponse;
import io.github.sachinnimbal.crudx.core.response.BatchResult;
import io.github.sachinnimbal.crudx.core.response.PageResponse;
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
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * @author Sachin Nimbal
 * @see <a href="https://www.linkedin.com/in/sachin-nimbal/">LinkedIn Profile</a>
 */
@Slf4j
public abstract class CrudXController<T extends CrudXBaseEntity<ID>, ID extends Serializable> {

    @Autowired
    protected ApplicationContext applicationContext;

    protected CrudXService<T, ID> crudService;

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

        String serviceBeanName = Character.toLowerCase(entityClass.getSimpleName().charAt(0)) +
                entityClass.getSimpleName().substring(1) + "Service";

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
    public ResponseEntity<ApiResponse<T>> create(@RequestBody T entity) {
        long startTime = System.currentTimeMillis();

        try {
            log.debug("Creating entity: {}", entityClass.getSimpleName());

            if (entity == null) {
                throw new IllegalArgumentException("Entity cannot be null");
            }

            T created = crudService.create(entity);

            long executionTime = System.currentTimeMillis() - startTime;

            log.info("Entity created successfully with ID: {} | Time taken: {} ms", created.getId(), executionTime);

            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(ApiResponse.success(created, "Entity created successfully", HttpStatus.CREATED, executionTime));
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
    public ResponseEntity<ApiResponse<BatchResult<T>>> createBatch(
            @RequestBody List<T> entities,
            @RequestParam(required = false, defaultValue = "true") boolean skipDuplicates) {

        long startTime = System.currentTimeMillis();

        try {
            log.debug("Creating batch of {} entities (skipDuplicates: {})", entities.size(), skipDuplicates);

            if (entities.isEmpty()) {
                throw new IllegalArgumentException("Entity list cannot be null or empty");
            }

            // Maximum limit - 100,000 records (1 Lakh)
            final int MAX_BATCH_SIZE = 100000;
            if (entities.size() > MAX_BATCH_SIZE) {
                throw new IllegalArgumentException(
                        String.format("Batch size exceeds maximum limit of %d (1 Lakh). Current size: %d. " +
                                        "Please split your request into multiple batches.",
                                MAX_BATCH_SIZE, entities.size())
                );
            }

            // Optimal chunk size for memory efficiency - tested for best performance
            // 500 records per chunk balances memory usage and database round trips
            final int OPTIMAL_CHUNK_SIZE = 500;

            // Always use chunking for batches > 100 to maintain consistent performance
            if (entities.size() > 100) {
                log.info("Processing batch of {} entities in chunks of {} (Memory-optimized mode)",
                        entities.size(), OPTIMAL_CHUNK_SIZE);

                BatchResult<T> combinedResult = new BatchResult<>();
                List<T> allCreated = new ArrayList<>((int)(entities.size() * 0.9)); // Pre-size for efficiency
                int totalSkipped = 0;
                List<String> allSkippedReasons = new ArrayList<>();

                int totalChunks = (entities.size() + OPTIMAL_CHUNK_SIZE - 1) / OPTIMAL_CHUNK_SIZE;
                int chunkNumber = 0;

                // Process in chunks with progress tracking
                for (int i = 0; i < entities.size(); i += OPTIMAL_CHUNK_SIZE) {
                    chunkNumber++;
                    int end = Math.min(i + OPTIMAL_CHUNK_SIZE, entities.size());

                    // Create new list to avoid memory leaks from subList
                    List<T> chunk = new ArrayList<>(entities.subList(i, end));

                    long chunkStart = System.currentTimeMillis();
                    log.debug("Processing chunk {}/{}: records {}-{}",
                            chunkNumber, totalChunks, i + 1, end);

                    try {
                        BatchResult<T> chunkResult = crudService.createBatch(chunk, skipDuplicates);

                        // Merge results
                        allCreated.addAll(chunkResult.getCreatedEntities());
                        totalSkipped += chunkResult.getSkippedCount();

                        if (chunkResult.getSkippedReasons() != null && !chunkResult.getSkippedReasons().isEmpty()) {
                            allSkippedReasons.addAll(chunkResult.getSkippedReasons());
                        }

                        long chunkTime = System.currentTimeMillis() - chunkStart;
                        log.debug("Chunk {}/{} completed: {} created, {} skipped | Time: {} ms",
                                chunkNumber, totalChunks,
                                chunkResult.getCreatedEntities().size(),
                                chunkResult.getSkippedCount(),
                                chunkTime);

                    } catch (Exception chunkError) {
                        log.error("Error processing chunk {}/{} (records {}-{}): {}",
                                chunkNumber, totalChunks, i + 1, end, chunkError.getMessage());

                        // Continue with next chunk if skipDuplicates=true
                        if (!skipDuplicates) {
                            throw chunkError;
                        }

                        totalSkipped += (end - i);
                        allSkippedReasons.add(String.format("Chunk %d/%d (records %d-%d) failed: %s",
                                chunkNumber, totalChunks, i + 1, end, chunkError.getMessage()));
                    } finally {
                        // Explicitly clear chunk to help GC
                        chunk.clear();
                    }

                    // Progress logging every 10 chunks or for large batches
                    if (chunkNumber % 10 == 0 || entities.size() > 10000) {
                        double progress = (double) end / entities.size() * 100;
                        long elapsed = System.currentTimeMillis() - startTime;
                        long estimated = (long) (elapsed / progress * 100);
                        log.info("Progress: {}/{} records ({}%) | Elapsed: {} ms | Estimated total: {} ms",
                                end, entities.size(), String.format("%.1f", progress), elapsed, estimated);
                    }
                }

                // Build combined result
                combinedResult.setCreatedEntities(allCreated);
                combinedResult.setSkippedCount(totalSkipped);
                if (!allSkippedReasons.isEmpty()) {
                    combinedResult.setSkippedReasons(allSkippedReasons);
                }

                long executionTime = System.currentTimeMillis() - startTime;
                double recordsPerSecond = (allCreated.size() * 1000.0) / executionTime;

                String message;
                if (combinedResult.hasSkipped()) {
                    message = String.format(
                            "Batch creation completed: %d created, %d skipped (duplicates/errors) | " +
                                    "Processed in %d chunks | Performance: %.0f records/sec",
                            allCreated.size(), totalSkipped, totalChunks, recordsPerSecond);
                } else {
                    message = String.format(
                            "%d entities created successfully | Processed in %d chunks | Performance: %.0f records/sec",
                            allCreated.size(), totalChunks, recordsPerSecond);
                }

                log.info("{} | Total time: {} ms", message, executionTime);

                return ResponseEntity.status(HttpStatus.CREATED)
                        .body(ApiResponse.success(combinedResult, message, HttpStatus.CREATED, executionTime));
            }

            // For very small batches (≤ 100), process without chunking
            BatchResult<T> result = crudService.createBatch(entities, skipDuplicates);

            long executionTime = System.currentTimeMillis() - startTime;

            String message;
            if (result.hasSkipped()) {
                message = String.format("Batch creation completed: %d created, %d skipped (duplicates/errors)",
                        result.getCreatedEntities().size(), result.getSkippedCount());
            } else {
                message = String.format("%d entities created successfully", result.getCreatedEntities().size());
            }

            log.info("{} | Time taken: {} ms", message, executionTime);

            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(ApiResponse.success(result, message, HttpStatus.CREATED, executionTime));

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
    public ResponseEntity<ApiResponse<T>> getById(@PathVariable ID id) {
        long startTime = System.currentTimeMillis();

        try {
            log.debug("Fetching entity by ID: {}", id);

            if (id == null) {
                throw new IllegalArgumentException("ID cannot be null");
            }

            T entity = crudService.findById(id);

            if (entity == null) {
                throw new EntityNotFoundException(entityClass.getSimpleName(), id);
            }

            long executionTime = System.currentTimeMillis() - startTime;

            log.info("Entity found with ID: {} | Time taken: {} ms", id, executionTime);

            return ResponseEntity.ok(ApiResponse.success(entity, "Entity retrieved successfully", executionTime));
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
                PageResponse<T> pageResponse = PageResponse.from(springPage);

                long executionTime = System.currentTimeMillis() - startTime;

                log.info("Retrieved first page of {} elements (total: {}) | Time taken: {} ms",
                        pageResponse.getContent().size(), totalCount, executionTime);

                return ResponseEntity.ok(ApiResponse.success(pageResponse,
                        String.format("Large dataset detected (%d total records). " +
                                        "Returning first %d records. Use /paged endpoint with page parameter for more data.",
                                totalCount, pageResponse.getContent().size()),
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

            long executionTime = System.currentTimeMillis() - startTime;

            log.info("Retrieved {} entities | Time taken: {} ms", entities.size(), executionTime);

            return ResponseEntity.ok(ApiResponse.success(entities,
                    String.format("Retrieved %d entities", entities.size()),
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
    public ResponseEntity<ApiResponse<PageResponse<T>>> getPaged(
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
            PageResponse<T> pageResponse = PageResponse.from(springPage);

            if (pageResponse.getTotalElements() == 0) {
                log.info("No entities found for page request");
                throw new EntityNotFoundException("No " + entityClass.getSimpleName() + " entities found");
            }

            long executionTime = System.currentTimeMillis() - startTime;

            log.info("Found page of {} {} entities (total: {}) | Time taken: {} ms",
                    pageResponse.getContent().size(),
                    entityClass.getSimpleName(),
                    pageResponse.getTotalElements(),
                    executionTime);

            String message = String.format("Retrieved page %d with %d elements (total: %d)",
                    page, pageResponse.getContent().size(), pageResponse.getTotalElements());

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
    public ResponseEntity<ApiResponse<T>> update(
            @PathVariable ID id,
            @RequestBody Map<String, Object> updates) {

        long startTime = System.currentTimeMillis();

        try {
            log.debug("Updating entity with ID: {}", id);

            if (id == null) {
                throw new IllegalArgumentException("ID cannot be null");
            }

            if (updates == null || updates.isEmpty()) {
                throw new IllegalArgumentException("Update data cannot be null or empty");
            }

            T updated = crudService.update(id, updates);

            if (updated == null) {
                throw new EntityNotFoundException(entityClass.getSimpleName(), id);
            }

            long executionTime = System.currentTimeMillis() - startTime;

            log.info("Entity updated successfully with ID: {} | Time taken: {} ms", id, executionTime);

            return ResponseEntity.ok(ApiResponse.success(updated, "Entity updated successfully", executionTime));
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

            if (!crudService.existsById(id)) {
                throw new EntityNotFoundException(entityClass.getSimpleName(), id);
            }

            crudService.delete(id);

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

            // If trying to delete more than the large dataset threshold, auto-limit
            if (ids.size() > LARGE_DATASET_THRESHOLD) {
                log.warn("Large deletion request detected ({} IDs). Auto-limiting to {} for safety",
                        ids.size(), LARGE_DATASET_THRESHOLD);

                // Limit to threshold
                List<ID> limitedIds = ids.subList(0, LARGE_DATASET_THRESHOLD);
                int notProcessedCount = ids.size() - LARGE_DATASET_THRESHOLD;

                // Service handles existence check and deletion
                BatchResult<ID> result = crudService.deleteBatch(limitedIds);

                // Add the size-limit skips
                result.setSkippedCount(result.getSkippedCount() + notProcessedCount);
                result.addSkippedReason(String.format("Deletion limited to %d records for safety. " +
                                "%d IDs were not processed. Use multiple batch requests for large deletions.",
                        LARGE_DATASET_THRESHOLD, notProcessedCount));

                long executionTime = System.currentTimeMillis() - startTime;

                log.info("Batch deletion completed with limitations: {} deleted, {} skipped | Time taken: {} ms",
                        result.getCreatedEntities().size(), result.getSkippedCount(), executionTime);

                return ResponseEntity.ok(ApiResponse.success(result,
                        String.format("Batch deletion completed with limitations: %d deleted, %d skipped",
                                result.getCreatedEntities().size(), result.getSkippedCount()),
                        executionTime));
            }

            // Process normal batch deletion - service handles everything
            BatchResult<ID> result = crudService.deleteBatch(ids);

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

            // Check if trying to delete more than threshold
            if (ids.size() > LARGE_DATASET_THRESHOLD) {
                throw new IllegalArgumentException(
                        String.format("Cannot force delete more than %d records at once. " +
                                        "Current request: %d IDs. Use regular /batch endpoint or multiple requests.",
                                LARGE_DATASET_THRESHOLD, ids.size()));
            }

            // Process in smaller batches to minimize memory footprint
            int batchSize = 100;
            int totalDeleted = 0;

            for (int i = 0; i < ids.size(); i += batchSize) {
                int end = Math.min(i + batchSize, ids.size());
                List<ID> batchIds = new ArrayList<>(ids.subList(i, end));

                crudService.deleteBatch(batchIds);
                totalDeleted += batchIds.size();

                // Clear batch to allow GC
                batchIds.clear();

                log.debug("Force deleted batch {}/{} entities (memory optimized)", totalDeleted, ids.size());
            }

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

    protected Class<T> getEntityClass() {
        return entityClass;
    }

    protected Class<ID> getIdClass() {
        return idClass;
    }

    // ===== Lifecycle Hook Methods - Override these for custom logic =====

    protected void beforeCreate(T entity) {
        // Default: no-op - override in subclass
    }

    protected void afterCreate(T entity) {
        // Default: no-op - override in subclass
    }

    protected void beforeCreateBatch(List<T> entities) {
        // Default: no-op - override in subclass
    }

    protected void afterCreateBatch(List<T> entities) {
        // Default: no-op - override in subclass
    }

    protected void beforeUpdate(ID id, Map<String, Object> updates, T existingEntity) {
        // Default: no-op - override in subclass
    }

    protected void afterUpdate(T updatedEntity, T oldEntity) {
        // Default: no-op - override in subclass
    }

    protected void beforeDelete(ID id, T entity) {
        // Default: no-op - override in subclass
    }

    protected void afterDelete(ID id, T deletedEntity) {
        // Default: no-op - override in subclass
    }

    protected void beforeDeleteBatch(List<ID> ids) {
        // Default: no-op - override in subclass
    }

    protected void afterDeleteBatch(List<ID> ids) {
        // Default: no-op - override in subclass
    }

    protected void afterFindById(T entity) {
        // Default: no-op - override in subclass
    }

    protected void afterFindAll(List<T> entities) {
        // Default: no-op - override in subclass
    }

    protected void afterFindPaged(PageResponse<T> pageResponse) {
        // Default: no-op - override in subclass
    }
}
