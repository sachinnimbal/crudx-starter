package io.github.sachinnimbal.crudx.web;

import io.github.sachinnimbal.crudx.core.enums.Direction;
import io.github.sachinnimbal.crudx.core.enums.OperationType;
import io.github.sachinnimbal.crudx.core.response.ApiResponse;
import io.github.sachinnimbal.crudx.dto.generator.DtoMapperGenerator;
import io.github.sachinnimbal.crudx.dto.generator.IncrementalMapperGenerator;
import io.github.sachinnimbal.crudx.dto.mapper.GeneratedMapper;
import io.github.sachinnimbal.crudx.dto.metadata.MapperMetadata;
import io.github.sachinnimbal.crudx.dto.registry.DtoRegistry;
import io.github.sachinnimbal.crudx.dto.registry.GeneratedMapperRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * ENHANCED: Mapper Inspection + Regeneration API
 */
@Slf4j
@RestController
@RequestMapping("/crudx/mappers")
@Tag(name = "CrudX Mapper Management", description = "Inspect and manage generated DTO mappers")
@ConditionalOnProperty(prefix = "crudx.dto", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MapperInspectionController {

    @Autowired(required = false)
    private GeneratedMapperRegistry mapperRegistry;

    @Autowired(required = false)
    private DtoRegistry dtoRegistry;

    @Autowired(required = false)
    private DtoMapperGenerator mapperGenerator;

    @Autowired(required = false)
    private IncrementalMapperGenerator incrementalGenerator;

    // ========== INSPECTION APIs (Existing) ==========

    @GetMapping
    @Operation(summary = "List all generated mappers")
    public ResponseEntity<ApiResponse<List<MapperMetadata>>> getAllMappers() {
        if (mapperRegistry == null) {
            return ResponseEntity.ok(ApiResponse.success(
                    Collections.emptyList(),
                    "DTO feature not enabled"
            ));
        }

        List<MapperMetadata> mappers = mapperRegistry.getAllMappers();

        return ResponseEntity.ok(ApiResponse.success(
                mappers,
                String.format("Found %d generated mapper(s)", mappers.size())
        ));
    }

    @GetMapping("/statistics")
    @Operation(summary = "Get mapper statistics")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getStatistics() {
        if (mapperRegistry == null) {
            return ResponseEntity.ok(ApiResponse.success(
                    Collections.emptyMap(),
                    "DTO feature not enabled"
            ));
        }

        Map<String, Object> stats = new HashMap<>();

        GeneratedMapperRegistry.MapperStatistics registryStats = mapperRegistry.getStatistics();
        stats.put("registry", registryStats);

        if (incrementalGenerator != null) {
            stats.put("incremental", incrementalGenerator.getStatistics());
        }

        return ResponseEntity.ok(ApiResponse.success(stats, "Statistics retrieved"));
    }

    @GetMapping("/entity/{entityName}")
    @Operation(summary = "Get mappers for entity")
    public ResponseEntity<ApiResponse<List<MapperMetadata>>> getMappersForEntity(
            @PathVariable String entityName) {

        if (mapperRegistry == null) {
            return ResponseEntity.ok(ApiResponse.success(
                    Collections.emptyList(),
                    "DTO feature not enabled"
            ));
        }

        List<MapperMetadata> mappers = mapperRegistry.getAllMappers().stream()
                .filter(m -> m.getEntityClass().getSimpleName().equalsIgnoreCase(entityName))
                .collect(Collectors.toList());

        return ResponseEntity.ok(ApiResponse.success(
                mappers,
                String.format("Found %d mapper(s) for entity: %s", mappers.size(), entityName)
        ));
    }

    @GetMapping("/dtos")
    @Operation(summary = "List all registered DTOs")
    public ResponseEntity<ApiResponse<List<DtoInfo>>> getAllDtos() {
        if (dtoRegistry == null) {
            return ResponseEntity.ok(ApiResponse.success(
                    Collections.emptyList(),
                    "DTO feature not enabled"
            ));
        }

        List<DtoInfo> dtos = dtoRegistry.getAllDtos().stream()
                .map(dtoClass -> DtoInfo.builder()
                        .className(dtoClass.getName())
                        .simpleName(dtoClass.getSimpleName())
                        .packageName(dtoClass.getPackageName())
                        .build())
                .collect(Collectors.toList());

        return ResponseEntity.ok(ApiResponse.success(
                dtos,
                String.format("Found %d registered DTO(s)", dtos.size())
        ));
    }

    // ========== NEW: ON-DEMAND GENERATION APIs ==========

    /**
     * Generate mapper on-demand for specific DTO-Entity pair
     */
    @PostMapping("/generate")
    @Operation(summary = "Generate mapper on-demand",
            description = "Creates a new mapper if it doesn't exist")
    public ResponseEntity<ApiResponse<GenerationResult>> generateMapper(
            @RequestBody GenerateMapperRequest request) {

        if (mapperGenerator == null || mapperRegistry == null) {
            return ResponseEntity.ok(ApiResponse.error(
                    "DTO feature not enabled",
                    org.springframework.http.HttpStatus.BAD_REQUEST
            ));
        }

        try {
            Class<?> entityClass = Class.forName(request.getEntityClass());
            Class<?> dtoClass = Class.forName(request.getDtoClass());
            OperationType operation = request.getOperation();
            Direction direction = request.getDirection();

            // Check if already exists
            if (mapperRegistry.hasMapper(entityClass, dtoClass)) {
                return ResponseEntity.ok(ApiResponse.success(
                        GenerationResult.builder()
                                .generated(false)
                                .reason("Mapper already exists")
                                .entityClass(request.getEntityClass())
                                .dtoClass(request.getDtoClass())
                                .build(),
                        "Mapper already exists"
                ));
            }

            // Generate
            log.info("Generating mapper on-demand: {} -> {} ({})",
                    dtoClass.getSimpleName(), entityClass.getSimpleName(), operation);

            GeneratedMapper<?, ?> mapper = mapperGenerator.generateMapper(
                    dtoClass, entityClass, operation, direction
            );

            if (mapper != null) {
                mapperRegistry.register(mapper);

                return ResponseEntity.ok(ApiResponse.success(
                        GenerationResult.builder()
                                .generated(true)
                                .reason("Successfully generated")
                                .entityClass(request.getEntityClass())
                                .dtoClass(request.getDtoClass())
                                .operation(operation)
                                .direction(direction)
                                .generatedClassName(mapper.getGeneratedClassName())
                                .build(),
                        "Mapper generated successfully"
                ));
            } else {
                return ResponseEntity.ok(ApiResponse.error(
                        "Mapper generation failed",
                        org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR
                ));
            }

        } catch (ClassNotFoundException e) {
            return ResponseEntity.ok(ApiResponse.error(
                    "Class not found: " + e.getMessage(),
                    org.springframework.http.HttpStatus.NOT_FOUND
            ));
        } catch (Exception e) {
            log.error("Error generating mapper", e);
            return ResponseEntity.ok(ApiResponse.error(
                    "Generation failed: " + e.getMessage(),
                    org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR
            ));
        }
    }

    /**
     * Batch generate multiple mappers at once
     */
    @PostMapping("/generate-batch")
    @Operation(summary = "Generate multiple mappers",
            description = "Creates multiple mappers in one request")
    public ResponseEntity<ApiResponse<BatchGenerationResult>> generateMappersBatch(
            @RequestBody List<GenerateMapperRequest> requests) {

        if (mapperGenerator == null || mapperRegistry == null) {
            return ResponseEntity.ok(ApiResponse.error(
                    "DTO feature not enabled",
                    org.springframework.http.HttpStatus.BAD_REQUEST
            ));
        }

        List<GenerationResult> results = new ArrayList<>();
        int successCount = 0;
        int skippedCount = 0;
        int failedCount = 0;

        for (GenerateMapperRequest request : requests) {
            try {
                Class<?> entityClass = Class.forName(request.getEntityClass());
                Class<?> dtoClass = Class.forName(request.getDtoClass());

                if (mapperRegistry.hasMapper(entityClass, dtoClass)) {
                    results.add(GenerationResult.builder()
                            .generated(false)
                            .reason("Already exists")
                            .entityClass(request.getEntityClass())
                            .dtoClass(request.getDtoClass())
                            .build());
                    skippedCount++;
                    continue;
                }

                GeneratedMapper<?, ?> mapper = mapperGenerator.generateMapper(
                        dtoClass, entityClass,
                        request.getOperation(),
                        request.getDirection()
                );

                if (mapper != null) {
                    mapperRegistry.register(mapper);
                    results.add(GenerationResult.builder()
                            .generated(true)
                            .reason("Success")
                            .entityClass(request.getEntityClass())
                            .dtoClass(request.getDtoClass())
                            .generatedClassName(mapper.getGeneratedClassName())
                            .build());
                    successCount++;
                } else {
                    results.add(GenerationResult.builder()
                            .generated(false)
                            .reason("Generation failed")
                            .entityClass(request.getEntityClass())
                            .dtoClass(request.getDtoClass())
                            .build());
                    failedCount++;
                }

            } catch (Exception e) {
                results.add(GenerationResult.builder()
                        .generated(false)
                        .reason("Error: " + e.getMessage())
                        .entityClass(request.getEntityClass())
                        .dtoClass(request.getDtoClass())
                        .build());
                failedCount++;
            }
        }

        BatchGenerationResult batchResult = BatchGenerationResult.builder()
                .totalRequested(requests.size())
                .successCount(successCount)
                .skippedCount(skippedCount)
                .failedCount(failedCount)
                .results(results)
                .build();

        return ResponseEntity.ok(ApiResponse.success(
                batchResult,
                String.format("Generated %d, Skipped %d, Failed %d",
                        successCount, skippedCount, failedCount)
        ));
    }

    // ========== NEW: INCREMENTAL REGENERATION APIs ==========

    /**
     * Smart regeneration: Only regenerates if fields changed
     */
    @PostMapping("/regenerate-smart")
    @Operation(summary = "Smart regenerate mapper",
            description = "Only regenerates if DTO/Entity fields changed")
    public ResponseEntity<ApiResponse<IncrementalMapperGenerator.RegenerationResult>> smartRegenerate(
            @RequestBody GenerateMapperRequest request) {

        if (incrementalGenerator == null) {
            return ResponseEntity.ok(ApiResponse.error(
                    "Incremental generation not available",
                    org.springframework.http.HttpStatus.BAD_REQUEST
            ));
        }

        try {
            Class<?> entityClass = Class.forName(request.getEntityClass());
            Class<?> dtoClass = Class.forName(request.getDtoClass());

            IncrementalMapperGenerator.RegenerationResult result =
                    incrementalGenerator.regenerateIfNeeded(
                            dtoClass, entityClass,
                            request.getOperation(),
                            request.getDirection()
                    );

            return ResponseEntity.ok(ApiResponse.success(
                    result,
                    result.isRegenerated() ? "Mapper regenerated" : "No regeneration needed"
            ));

        } catch (ClassNotFoundException e) {
            return ResponseEntity.ok(ApiResponse.error(
                    "Class not found: " + e.getMessage(),
                    org.springframework.http.HttpStatus.NOT_FOUND
            ));
        }
    }

    /**
     * Smart regeneration for all registered mappers
     */
    @PostMapping("/regenerate-all-smart")
    @Operation(summary = "Smart regenerate all mappers",
            description = "Checks and regenerates only changed mappers")
    public ResponseEntity<ApiResponse<SmartRegenerationSummary>> smartRegenerateAll() {
        if (incrementalGenerator == null || mapperRegistry == null) {
            return ResponseEntity.ok(ApiResponse.error(
                    "Incremental generation not available",
                    org.springframework.http.HttpStatus.BAD_REQUEST
            ));
        }

        long startTime = System.currentTimeMillis();
        List<IncrementalMapperGenerator.RegenerationResult> results = new ArrayList<>();
        int regeneratedCount = 0;
        int skippedCount = 0;

        List<MapperMetadata> allMappers = mapperRegistry.getAllMappers();

        for (MapperMetadata metadata : allMappers) {
            try {
                IncrementalMapperGenerator.RegenerationResult result =
                        incrementalGenerator.regenerateIfNeeded(
                                metadata.getDtoClass(),
                                metadata.getEntityClass(),
                                metadata.getOperation(),
                                metadata.getDirection()
                        );

                results.add(result);

                if (result.isRegenerated()) {
                    regeneratedCount++;
                } else {
                    skippedCount++;
                }

            } catch (Exception e) {
                log.error("Error during smart regeneration", e);
            }
        }

        long duration = System.currentTimeMillis() - startTime;

        SmartRegenerationSummary summary = SmartRegenerationSummary.builder()
                .totalMappers(allMappers.size())
                .regeneratedCount(regeneratedCount)
                .skippedCount(skippedCount)
                .durationMs(duration)
                .results(results)
                .build();

        return ResponseEntity.ok(ApiResponse.success(
                summary,
                String.format("Smart regeneration completed: %d regenerated, %d skipped in %dms",
                        regeneratedCount, skippedCount, duration)
        ));
    }

    /**
     * Force regenerate specific mapper (ignores change detection)
     */
    @PostMapping("/regenerate-force")
    @Operation(summary = "Force regenerate mapper",
            description = "Regenerates mapper even if no changes detected")
    public ResponseEntity<ApiResponse<GenerationResult>> forceRegenerate(
            @RequestBody GenerateMapperRequest request) {

        if (mapperGenerator == null || mapperRegistry == null) {
            return ResponseEntity.ok(ApiResponse.error(
                    "DTO feature not enabled",
                    org.springframework.http.HttpStatus.BAD_REQUEST
            ));
        }

        try {
            Class<?> entityClass = Class.forName(request.getEntityClass());
            Class<?> dtoClass = Class.forName(request.getDtoClass());

            log.info("Force regenerating mapper: {} -> {}",
                    dtoClass.getSimpleName(), entityClass.getSimpleName());

            GeneratedMapper<?, ?> mapper = mapperGenerator.generateMapper(
                    dtoClass, entityClass,
                    request.getOperation(),
                    request.getDirection()
            );

            if (mapper != null) {
                mapperRegistry.register(mapper);

                return ResponseEntity.ok(ApiResponse.success(
                        GenerationResult.builder()
                                .generated(true)
                                .reason("Force regenerated")
                                .entityClass(request.getEntityClass())
                                .dtoClass(request.getDtoClass())
                                .generatedClassName(mapper.getGeneratedClassName())
                                .build(),
                        "Mapper force regenerated successfully"
                ));
            } else {
                return ResponseEntity.ok(ApiResponse.error(
                        "Regeneration failed",
                        org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR
                ));
            }

        } catch (ClassNotFoundException e) {
            return ResponseEntity.ok(ApiResponse.error(
                    "Class not found: " + e.getMessage(),
                    org.springframework.http.HttpStatus.NOT_FOUND
            ));
        } catch (Exception e) {
            log.error("Error force regenerating mapper", e);
            return ResponseEntity.ok(ApiResponse.error(
                    "Regeneration failed: " + e.getMessage(),
                    org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR
            ));
        }
    }

    /**
     * Clear all mappers and regenerate from scratch
     */
    @PostMapping("/regenerate-all-force")
    @Operation(summary = "Force regenerate all mappers",
            description = "Clears registry and regenerates all mappers")
    public ResponseEntity<ApiResponse<String>> forceRegenerateAll() {
        if (mapperRegistry == null || dtoRegistry == null) {
            return ResponseEntity.ok(ApiResponse.error(
                    "DTO feature not enabled",
                    org.springframework.http.HttpStatus.BAD_REQUEST
            ));
        }

        try {
            long startTime = System.currentTimeMillis();

            // Clear registry
            mapperRegistry.clear();

            if (incrementalGenerator != null) {
                incrementalGenerator.clearSignatures();
            }

            log.info("Cleared all mappers, triggering full regeneration...");

            // Trigger full regeneration via scanner
            // Note: This will be picked up by CrudXDtoScannerConfiguration

            long duration = System.currentTimeMillis() - startTime;

            return ResponseEntity.ok(ApiResponse.success(
                    String.format("Registry cleared. Mappers will regenerate on next use. Duration: %dms", duration),
                    "Full regeneration triggered"
            ));

        } catch (Exception e) {
            log.error("Error during full regeneration", e);
            return ResponseEntity.ok(ApiResponse.error(
                    "Full regeneration failed: " + e.getMessage(),
                    org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR
            ));
        }
    }

    // ========== DTOs ==========

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GenerateMapperRequest {
        private String entityClass;
        private String dtoClass;
        private OperationType operation;
        private Direction direction;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GenerationResult {
        private boolean generated;
        private String reason;
        private String entityClass;
        private String dtoClass;
        private OperationType operation;
        private Direction direction;
        private String generatedClassName;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BatchGenerationResult {
        private int totalRequested;
        private int successCount;
        private int skippedCount;
        private int failedCount;
        private List<GenerationResult> results;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SmartRegenerationSummary {
        private int totalMappers;
        private int regeneratedCount;
        private int skippedCount;
        private long durationMs;
        private List<IncrementalMapperGenerator.RegenerationResult> results;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DtoInfo {
        private String className;
        private String simpleName;
        private String packageName;
    }
}