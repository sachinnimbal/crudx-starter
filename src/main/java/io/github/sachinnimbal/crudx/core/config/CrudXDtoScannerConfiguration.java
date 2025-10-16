package io.github.sachinnimbal.crudx.core.config;

import io.github.sachinnimbal.crudx.core.annotations.dto.CrudXRequestDto;
import io.github.sachinnimbal.crudx.core.annotations.dto.CrudXResponseDto;
import io.github.sachinnimbal.crudx.dto.generator.CrudXMapperGenerator;
import io.github.sachinnimbal.crudx.dto.mapper.CrudXEntityMapper;
import io.github.sachinnimbal.crudx.dto.registry.CrudXDtoRegistry;
import io.github.sachinnimbal.crudx.dto.registry.CrudXMapperRegistry;
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

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "crudx.dto", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CrudXDtoScannerConfiguration {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired(required = false)
    private CrudXDtoRegistry crudXDtoRegistry;

    @Autowired(required = false)
    private CrudXMapperGenerator mapperGenerator;

    @Autowired(required = false)
    private CrudXMapperRegistry mapperRegistry;

    @Autowired(required = false)
    private CrudXDtoProperties properties;

    @PostConstruct
    public void scanAndGenerateMappers() {
        if (crudXDtoRegistry == null || mapperGenerator == null) {
            log.debug("DTO components not available - skipping DTO scanning");
            return;
        }

        long startTime = System.currentTimeMillis();

        try {
            String[] packagesToScan = getPackagesToScan();
            log.info("🔍 Scanning for CrudX DTOs in packages: {}", String.join(", ", packagesToScan));

            Map<Class<?>, Set<Class<?>>> entityToDtos = new HashMap<>();
            PathMatchingResourcePatternResolver scanner = new PathMatchingResourcePatternResolver();
            CachingMetadataReaderFactory metadataReaderFactory = new CachingMetadataReaderFactory();

            int totalDtosFound = 0;

            for (String basePackage : packagesToScan) {
                String pattern = "classpath*:" + basePackage.replace('.', '/') + "/**/*.class";
                Resource[] resources = scanner.getResources(pattern);

                for (Resource resource : resources) {
                    try {
                        MetadataReader metadataReader = metadataReaderFactory.getMetadataReader(resource);
                        String className = metadataReader.getClassMetadata().getClassName();
                        Class<?> clazz = Class.forName(className);

                        // Check for REQUEST or RESPONSE DTO annotations
                        if (clazz.isAnnotationPresent(CrudXRequestDto.class) ||
                                clazz.isAnnotationPresent(CrudXResponseDto.class)) {

                            crudXDtoRegistry.registerDto(clazz);
                            totalDtosFound++;

                            // Track entity -> DTOs
                            Class<?> entityClass = getEntityClassFromDto(clazz);
                            if (entityClass != null) {
                                entityToDtos.computeIfAbsent(entityClass, k -> new HashSet<>()).add(clazz);
                            }
                        }
                    } catch (Exception e) {
                        log.trace("Could not load class from resource: {}", resource.getFilename());
                    }
                }
            }

            if (entityToDtos.isEmpty()) {
                printNoMappersWarning();
                return;
            }

            log.info("🔧 Generating unified mappers for {} entities...", entityToDtos.size());

            AtomicInteger generatedCount = new AtomicInteger(0);

            entityToDtos.forEach((entityClass, dtoClasses) -> {
                try {
                    log.debug("Generating mapper for {} with {} DTOs: {}",
                            entityClass.getSimpleName(),
                            dtoClasses.size(),
                            dtoClasses.stream().map(Class::getSimpleName).toList());

                    CrudXEntityMapper<?, ?> mapper = mapperGenerator.getOrGenerateMapper(entityClass, dtoClasses);

                    if (mapper != null) {
                        mapperRegistry.register(mapper);
                        generatedCount.incrementAndGet();
                    }

                } catch (Exception e) {
                    log.error("Failed to generate mapper for {}: {}",
                            entityClass.getSimpleName(), e.getMessage());
                }
            });

            long duration = System.currentTimeMillis() - startTime;
            printSummary(entityToDtos.size(), totalDtosFound, generatedCount.get(), duration);

        } catch (Exception e) {
            log.error("Error during DTO scanning and mapper generation", e);
        }
    }

    /**
     * Extract entity class from any DTO annotation type
     */
    private Class<?> getEntityClassFromDto(Class<?> dtoClass) {
        if (dtoClass.isAnnotationPresent(CrudXRequestDto.class)) {
            return dtoClass.getAnnotation(CrudXRequestDto.class).entity();
        }
        if (dtoClass.isAnnotationPresent(CrudXResponseDto.class)) {
            return dtoClass.getAnnotation(CrudXResponseDto.class).entity();
        }
        return null;
    }

    private void printSummary(int totalEntities, int totalDtos, int generatedMappers, long duration) {
        log.info("╔════════════════════════════════════════════════════════╗");
        log.info("║      CrudX Unified Mapper Generation Complete        ║");
        log.info("╠════════════════════════════════════════════════════════╣");
        log.info("║  ✓ Entities:          {} {}",
                String.format("%-6d", totalEntities), " ".repeat(30));
        log.info("║  ✓ DTOs Found:        {} {}",
                String.format("%-6d", totalDtos), " ".repeat(30));
        log.info("║  ✓ Mappers Generated: {} {}",
                String.format("%-6d", generatedMappers), " ".repeat(30));
        log.info("║  ✓ Time Taken:        {} ms {}",
                String.format("%-6d", duration), " ".repeat(27));
        log.info("╠════════════════════════════════════════════════════════╣");
        log.info("║  Architecture: ONE MAPPER PER ENTITY                  ║");
        log.info("║  Average DTOs per Entity: {} {}",
                String.format("%.1f", totalDtos / (double) Math.max(1, totalEntities)),
                " ".repeat(24));
        log.info("╚════════════════════════════════════════════════════════╝");

        log.info("📁 Generated Mapper Location:");
        log.info("  → Maven: target/generated-sources/crudx-mappers/");
        log.info("  → Gradle: build/generated/sources/crudx-mappers/");
        log.info("  → Package: io.github.sachinnimbal.crudx.generated.mappers");

        if (mapperRegistry != null) {
            CrudXMapperRegistry.MapperStatistics stats = mapperRegistry.getStatistics();
            log.info("📊 Statistics:");
            log.info("  → Total Mappers: {}", stats.getTotalMappers());
            log.info("  → Total Entities: {}", stats.getTotalEntities());
            log.info("  → Total DTOs: {}", stats.getTotalDtos());
        }
    }

    private void printNoMappersWarning() {
        log.warn("╔════════════════════════════════════════════════════════╗");
        log.warn("║             No CrudX DTOs Found                       ║");
        log.warn("╠════════════════════════════════════════════════════════╣");
        log.warn("║  Add @CrudXRequestDto or @CrudXResponseDto:           ║");
        log.warn("║                                                        ║");
        log.warn("║  @CrudXRequestDto(                                     ║");
        log.warn("║    entity = User.class,                                ║");
        log.warn("║    operations = OperationType.CREATE                   ║");
        log.warn("║  )                                                     ║");
        log.warn("║  public class UserCreateRequest { ... }                ║");
        log.warn("╚════════════════════════════════════════════════════════╝");
    }

    private String[] getPackagesToScan() {
        // Priority 1: User-configured packages
        if (properties != null && properties.getScanPackages() != null &&
                properties.getScanPackages().length > 0) {
            log.info("Using configured scan packages: {}",
                    String.join(", ", properties.getScanPackages()));
            return properties.getScanPackages();
        }

        // Priority 2: Detect from Spring Boot main class
        try {
            String mainClass = deduceMainApplicationClass();
            if (mainClass != null) {
                String basePackage = mainClass.substring(0, mainClass.lastIndexOf('.'));
                log.info("Auto-detected base package from @SpringBootApplication: {}", basePackage);
                return new String[]{basePackage};
            }
        } catch (Exception e) {
            log.debug("Could not detect main class: {}", e.getMessage());
        }

        // Priority 3: Scan from all @Entity classes
        try {
            Set<String> entityPackages = scanEntityPackages();
            if (!entityPackages.isEmpty()) {
                String[] packages = entityPackages.toArray(new String[0]);
                log.info("Auto-detected packages from @Entity classes: {}",
                        String.join(", ", packages));
                return packages;
            }
        } catch (Exception e) {
            log.debug("Could not scan entity packages: {}", e.getMessage());
        }

        // Priority 4: Fallback - throw exception instead of guessing
        throw new IllegalStateException(
                "Cannot determine base package for DTO scanning. Please configure:\n" +
                        "  crudx.dto.scan-packages=com.yourcompany.yourapp\n" +
                        "Or annotate your main class with @SpringBootApplication"
        );
    }

    /**
     * Find the main Spring Boot application class
     */
    private String deduceMainApplicationClass() {
        try {
            StackTraceElement[] stackTrace = new RuntimeException().getStackTrace();
            for (StackTraceElement element : stackTrace) {
                if ("main".equals(element.getMethodName())) {
                    return element.getClassName();
                }
            }

            // Alternative: Check for @SpringBootApplication
            Map<String, Object> beans = applicationContext.getBeansWithAnnotation(
                    org.springframework.boot.autoconfigure.SpringBootApplication.class);
            if (!beans.isEmpty()) {
                Object bean = beans.values().iterator().next();
                return bean.getClass().getPackage().getName();
            }
        } catch (Exception e) {
            log.debug("Error deducing main class", e);
        }
        return null;
    }

    /**
     * Scan all @Entity annotated classes and extract their packages
     */
    private Set<String> scanEntityPackages() {
        Set<String> packages = new HashSet<>();
        try {
            Map<String, Object> entities = applicationContext.getBeansWithAnnotation(
                    jakarta.persistence.Entity.class);

            for (Object entity : entities.values()) {
                String packageName = entity.getClass().getPackage().getName();
                // Get parent package (assume DTOs are in sibling packages)
                if (packageName.contains(".")) {
                    String parentPackage = packageName.substring(0, packageName.lastIndexOf('.'));
                    packages.add(parentPackage);
                }
            }

            // Also check for MongoDB documents
            Map<String, Object> documents = applicationContext.getBeansWithAnnotation(
                    org.springframework.data.mongodb.core.mapping.Document.class);

            for (Object doc : documents.values()) {
                String packageName = doc.getClass().getPackage().getName();
                if (packageName.contains(".")) {
                    String parentPackage = packageName.substring(0, packageName.lastIndexOf('.'));
                    packages.add(parentPackage);
                }
            }
        } catch (Exception e) {
            log.debug("Error scanning entity packages", e);
        }
        return packages;
    }
}