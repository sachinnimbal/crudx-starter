package io.github.sachinnimbal.crudx.core.config;

import io.github.sachinnimbal.crudx.core.annotations.dto.*;
import io.github.sachinnimbal.crudx.core.enums.Direction;
import io.github.sachinnimbal.crudx.core.enums.OperationType;
import io.github.sachinnimbal.crudx.dto.generator.DtoMapperGenerator;
import io.github.sachinnimbal.crudx.dto.mapper.GeneratedMapper;
import io.github.sachinnimbal.crudx.dto.registry.DtoRegistry;
import io.github.sachinnimbal.crudx.dto.registry.GeneratedMapperRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReader;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Optimized scanner that generates ONE mapper file per Entity-DTO pair
 * (instead of multiple files per operation)
 */
@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "crudx.dto", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CrudXDtoScannerConfiguration {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired(required = false)
    private DtoRegistry dtoRegistry;

    @Autowired(required = false)
    private DtoMapperGenerator mapperGenerator;

    @Autowired(required = false)
    private GeneratedMapperRegistry mapperRegistry;

    @Autowired(required = false)
    private CrudXDtoProperties properties;

    @PostConstruct
    public void scanAndGenerateMappers() {
        if (dtoRegistry == null || mapperGenerator == null) {
            log.debug("DTO components not available - skipping DTO scanning");
            return;
        }

        long startTime = System.currentTimeMillis();

        // Use Set to track unique entity-DTO pairs (avoid duplicates)
        Set<EntityDtoPair> uniquePairs = new HashSet<>();

        try {
            // Step 1: Scan for DTOs
            String[] packagesToScan = getPackagesToScan();
            log.info("🔍 Scanning for CrudX DTOs in packages: {}", String.join(", ", packagesToScan));

            PathMatchingResourcePatternResolver scanner = new PathMatchingResourcePatternResolver();
            CachingMetadataReaderFactory metadataReaderFactory = new CachingMetadataReaderFactory();

            for (String basePackage : packagesToScan) {
                String pattern = "classpath*:" + basePackage.replace('.', '/') + "/**/*.class";
                Resource[] resources = scanner.getResources(pattern);

                for (Resource resource : resources) {
                    try {
                        MetadataReader metadataReader = metadataReaderFactory.getMetadataReader(resource);
                        String className = metadataReader.getClassMetadata().getClassName();
                        Class<?> clazz = Class.forName(className);

                        if (isDtoClass(clazz)) {
                            dtoRegistry.registerDto(clazz);

                            // Extract unique entity-DTO pairs
                            uniquePairs.addAll(extractEntityDtoPairs(clazz));
                        }
                    } catch (Exception e) {
                        log.trace("Could not load class from resource: {}", resource.getFilename());
                    }
                }
            }

            // Step 2: Generate ONE mapper per unique entity-DTO pair
            AtomicInteger generatedCount = new AtomicInteger(0);

            if (!uniquePairs.isEmpty()) {
                log.info("🔧 Generating mappers for {} unique entity-DTO pair(s)...", uniquePairs.size());

                uniquePairs.forEach(pair -> {
                    try {
                        // Generate single unified mapper (not per operation)
                        GeneratedMapper<?, ?> mapper = mapperGenerator.generateMapper(
                                pair.dtoClass,
                                pair.entityClass,
                                OperationType.GET_BY_ID, // Operation doesn't matter for unified mapper
                                Direction.RESPONSE
                        );

                        if (mapper != null) {
                            mapperRegistry.register(mapper);
                            generatedCount.incrementAndGet();

                            log.debug("  ✓ Generated: {} <-> {}Mapper",
                                    pair.entityClass.getSimpleName(),
                                    pair.dtoClass.getSimpleName());
                        }

                    } catch (Exception e) {
                        log.warn("Failed to generate mapper for {} <-> {}: {}",
                                pair.entityClass.getSimpleName(),
                                pair.dtoClass.getSimpleName(),
                                e.getMessage());
                    }
                });
            }

            long duration = System.currentTimeMillis() - startTime;

            // Print summary
            printSummary(uniquePairs.size(), generatedCount.get(), duration);

        } catch (Exception e) {
            log.error("Error during DTO scanning and mapper generation", e);
        }
    }

    /**
     * Extract unique entity-DTO pairs from DTO class annotations
     */
    private Set<EntityDtoPair> extractEntityDtoPairs(Class<?> dtoClass) {
        Set<EntityDtoPair> pairs = new HashSet<>();

        if (dtoClass.isAnnotationPresent(CrudXCreateRequestDto.class)) {
            CrudXCreateRequestDto annotation = dtoClass.getAnnotation(CrudXCreateRequestDto.class);
            pairs.add(new EntityDtoPair(annotation.entity(), dtoClass));
        }

        if (dtoClass.isAnnotationPresent(CrudXUpdateRequestDto.class)) {
            CrudXUpdateRequestDto annotation = dtoClass.getAnnotation(CrudXUpdateRequestDto.class);
            pairs.add(new EntityDtoPair(annotation.entity(), dtoClass));
        }

        if (dtoClass.isAnnotationPresent(CrudXBatchCreateRequestDto.class)) {
            CrudXBatchCreateRequestDto annotation = dtoClass.getAnnotation(CrudXBatchCreateRequestDto.class);
            pairs.add(new EntityDtoPair(annotation.entity(), dtoClass));
        }

        if (dtoClass.isAnnotationPresent(CrudXResponseDto.class)) {
            CrudXResponseDto annotation = dtoClass.getAnnotation(CrudXResponseDto.class);
            pairs.add(new EntityDtoPair(annotation.entity(), dtoClass));
        }

        if (dtoClass.isAnnotationPresent(CrudXDto.class)) {
            CrudXDto annotation = dtoClass.getAnnotation(CrudXDto.class);
            pairs.add(new EntityDtoPair(annotation.entity(), dtoClass));
        }

        return pairs;
    }

    private void printSummary(int totalPairs, int generatedMappers, long duration) {
        if (totalPairs == 0) {
            log.warn("╔════════════════════════════════════════════════════════╗");
            log.warn("║          No CrudX DTOs Found                          ║");
            log.warn("╠════════════════════════════════════════════════════════╣");
            log.warn("║  Add @CrudXResponseDto, @CrudXCreateRequestDto, etc.  ║");
            log.warn("║  to your DTO classes to enable auto-mapping           ║");
            log.warn("╚════════════════════════════════════════════════════════╝");
            return;
        }

        log.info("╔════════════════════════════════════════════════════════╗");
        log.info("║        CrudX DTO Auto-Generation Completed            ║");
        log.info("╠════════════════════════════════════════════════════════╣");
        log.info("║  ✓ Entity-DTO Pairs:  {} {}",
                String.format("%-6d", totalPairs),
                " ".repeat(30));
        log.info("║  ✓ Generated Mappers: {} {}",
                String.format("%-6d", generatedMappers),
                " ".repeat(30));
        log.info("║  ✓ Time Taken:        {} ms {}",
                String.format("%-6d", duration),
                " ".repeat(27));
        log.info("╚════════════════════════════════════════════════════════╝");

        // Show mapper location
        log.info("📁 Mapper Location:");
        log.info("  → Maven: target/generated-sources/crudx-mappers/");
        log.info("  → Gradle: build/generated/sources/crudx-mappers/");
        log.info("  → Package: io.github.sachinnimbal.crudx.generated.mappers");

        // Show statistics
        if (mapperRegistry != null) {
            GeneratedMapperRegistry.MapperStatistics stats = mapperRegistry.getStatistics();
            log.info("Statistics:");
            log.info("  → Total Mappers: {}", stats.getTotalMappers());
            log.info("  → Unique Entities: {}", stats.getEntities());
            log.info("  → Unique DTOs: {}", stats.getDtos());
        }
    }

    private String[] getPackagesToScan() {
        if (properties != null && properties.getScanPackages() != null &&
                properties.getScanPackages().length > 0) {
            return properties.getScanPackages();
        }

        String mainClassName = System.getProperty("sun.java.command");
        if (mainClassName != null && mainClassName.contains(".")) {
            String basePackage = mainClassName.substring(0, mainClassName.lastIndexOf('.'));
            log.debug("Auto-detected base package: {}", basePackage);
            return new String[]{basePackage};
        }

        log.warn("Could not detect base package. Scanning common patterns.");
        return new String[]{"com", "org", "io"};
    }

    private boolean isDtoClass(Class<?> clazz) {
        return clazz.isAnnotationPresent(CrudXResponseDto.class) ||
                clazz.isAnnotationPresent(CrudXCreateRequestDto.class) ||
                clazz.isAnnotationPresent(CrudXUpdateRequestDto.class) ||
                clazz.isAnnotationPresent(CrudXBatchCreateRequestDto.class) ||
                clazz.isAnnotationPresent(CrudXDto.class);
    }

    /**
     * Represents a unique Entity-DTO pair
     */
    private static class EntityDtoPair {
        private final Class<?> entityClass;
        private final Class<?> dtoClass;

        public EntityDtoPair(Class<?> entityClass, Class<?> dtoClass) {
            this.entityClass = entityClass;
            this.dtoClass = dtoClass;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof EntityDtoPair)) return false;
            EntityDtoPair that = (EntityDtoPair) o;
            return entityClass.equals(that.entityClass) && dtoClass.equals(that.dtoClass);
        }

        @Override
        public int hashCode() {
            return Objects.hash(entityClass, dtoClass);
        }

        @Override
        public String toString() {
            return entityClass.getSimpleName() + " <-> " + dtoClass.getSimpleName();
        }
    }
}