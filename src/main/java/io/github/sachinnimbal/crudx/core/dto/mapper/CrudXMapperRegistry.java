package io.github.sachinnimbal.crudx.core.dto.mapper;

import io.github.sachinnimbal.crudx.core.dto.annotations.CrudXRequest;
import io.github.sachinnimbal.crudx.core.dto.annotations.CrudXResponse;
import io.github.sachinnimbal.crudx.core.enums.CrudXOperation;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.env.Environment;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 🚀 Optimized DTO Registry with Smart Package Scanning
 * <p>
 * Features:
 * - Scans ONLY packages specified in crudx.dto.scan-packages
 * - Fast startup (no full classpath scan)
 * - Automatic mapper bean creation
 * - Thread-safe concurrent access
 * <p>
 * Configuration:
 * crudx.dto.scan-packages=com.crudx.examples,com.myapp.dto
 *
 * @author Sachin Nimbal
 * @since 1.0.2
 */
@Slf4j
@Component
public class CrudXMapperRegistry {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private Environment environment;

    // entityClass -> operation -> requestDtoClass
    private final Map<Class<?>, Map<CrudXOperation, Class<?>>> requestMappings = new ConcurrentHashMap<>();

    // entityClass -> operation -> responseDtoClass
    private final Map<Class<?>, Map<CrudXOperation, Class<?>>> responseMappings = new ConcurrentHashMap<>();

    // entityClass -> mapper instance
    private final Map<Class<?>, CrudXMapper<?, ?, ?>> mappers = new ConcurrentHashMap<>();

    @Autowired(required = false)
    private CrudXDynamicMapperFactory mapperFactory;

    private boolean initialized = false;

    @PostConstruct
    public void initialize() {
        if (initialized) return;

        long startTime = System.currentTimeMillis();
        log.info("🔍 CrudX DTO Registry - Starting initialization...");

        // Get scan packages from properties
        String scanPackages = environment.getProperty("crudx.dto.scan-packages", "");

        if (scanPackages.isEmpty()) {
            log.warn("⚠️  No scan packages configured. Set 'crudx.dto.scan-packages' property.");
            log.warn("   Example: crudx.dto.scan-packages=com.example.controller,com.example.dto");
            return;
        }

        // Scan only specified packages
        scanAndRegisterDTOs(scanPackages);

        // Create mapper beans
        createMapperBeans();

        long executionTime = System.currentTimeMillis() - startTime;

        log.info("✅ DTO Registry initialized successfully");
        log.info("   📦 Scanned packages: {}", scanPackages);
        log.info("   🎯 Entities with DTOs: {}", getRegisteredEntities().size());
        log.info("   ⏱️  Initialization time: {} ms", executionTime);

        initialized = true;
    }

    /**
     * Smart package scanning - ONLY scans specified packages
     */
    private void scanAndRegisterDTOs(String basePackages) {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);

        scanner.addIncludeFilter(new AnnotationTypeFilter(CrudXRequest.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(CrudXResponse.class));

        // 🔥 PARALLEL SCANNING for faster startup
        String[] packages = basePackages.split(",");
        int totalDTOs = packages.length > 1 ?
                scanPackagesParallel(scanner, packages) :
                scanPackagesSequential(scanner, packages);

        log.info("📊 Total DTOs discovered: {}", totalDTOs);
    }

    private int scanPackagesParallel(ClassPathScanningCandidateComponentProvider scanner,
                                     String[] packages) {
        return Arrays.stream(packages)
                .parallel()
                .mapToInt(pkg -> scanSinglePackage(scanner, pkg.trim()))
                .sum();
    }

    private int scanPackagesSequential(ClassPathScanningCandidateComponentProvider scanner,
                                       String[] packages) {
        int total = 0;
        for (String pkg : packages) {
            total += scanSinglePackage(scanner, pkg.trim());
        }
        return total;
    }

    private int scanSinglePackage(ClassPathScanningCandidateComponentProvider scanner, String pkg) {
        if (pkg.isEmpty()) return 0;

        log.debug("📂 Scanning package: {}", pkg);
        int count = 0;

        try {
            var candidates = scanner.findCandidateComponents(pkg);

            for (var beanDef : candidates) {
                try {
                    Class<?> dtoClass = Class.forName(beanDef.getBeanClassName());

                    if (dtoClass.isAnnotationPresent(CrudXRequest.class)) {
                        registerRequestDTO(dtoClass);
                        count++;
                    }

                    if (dtoClass.isAnnotationPresent(CrudXResponse.class)) {
                        registerResponseDTO(dtoClass);
                        count++;
                    }
                } catch (ClassNotFoundException e) {
                    log.error("❌ Failed to load DTO: {}", beanDef.getBeanClassName());
                }
            }

            log.debug("   ✓ Found {} DTO(s) in {}", candidates.size(), pkg);
        } catch (Exception e) {
            log.error("❌ Error scanning package {}: {}", pkg, e.getMessage());
        }

        return count;
    }

    /**
     * Create mapper beans for all registered entities
     */
    private void createMapperBeans() {
        if (mapperFactory == null) {
            log.warn("⚠️  CrudXDynamicMapperFactory not available - mappers will not be created");
            return;
        }

        ConfigurableApplicationContext context = (ConfigurableApplicationContext) applicationContext;
        BeanDefinitionRegistry registry = (BeanDefinitionRegistry) context.getBeanFactory();

        int createdCount = 0;

        for (Class<?> entityClass : getRegisteredEntities()) {
            try {
                mapperFactory.createMapperBean(entityClass, registry, this);
                createdCount++;
            } catch (Exception e) {
                log.error("❌ Failed to create mapper bean for {}: {}",
                        entityClass.getSimpleName(), e.getMessage());
            }
        }

        log.info("🔧 Created {} mapper beans", createdCount);
    }

    /**
     * Get request DTO class for entity and operation
     */
    public Optional<Class<?>> getRequestDTO(Class<?> entityClass, CrudXOperation operation) {
        return Optional.ofNullable(requestMappings.get(entityClass))
                .map(map -> map.get(operation));
    }

    /**
     * Get response DTO class for entity and operation
     */
    public Optional<Class<?>> getResponseDTO(Class<?> entityClass, CrudXOperation operation) {
        return Optional.ofNullable(responseMappings.get(entityClass))
                .map(map -> map.get(operation));
    }

    private void registerRequestDTO(Class<?> dtoClass) {
        CrudXRequest annotation = dtoClass.getAnnotation(CrudXRequest.class);
        Class<?> entityClass = annotation.value();
        CrudXOperation[] operations = annotation.operations();

        if (operations.length == 0) {
            // Default: all mutation operations
            operations = new CrudXOperation[]{
                    CrudXOperation.CREATE, CrudXOperation.BATCH_CREATE,
                    CrudXOperation.UPDATE, CrudXOperation.BATCH_UPDATE
            };
        }

        Map<CrudXOperation, Class<?>> opMap = requestMappings.computeIfAbsent(
                entityClass, k -> new ConcurrentHashMap<>());

        for (CrudXOperation op : operations) {
            if (opMap.containsKey(op)) {
                log.warn("⚠️  Duplicate request DTO for {} operation on {} entity: {} (overriding {})",
                        op, entityClass.getSimpleName(), dtoClass.getSimpleName(),
                        opMap.get(op).getSimpleName());
            }
            opMap.put(op, dtoClass);
        }

        log.debug("✓ Request DTO: {} -> {} for operations: {}",
                dtoClass.getSimpleName(), entityClass.getSimpleName(), Arrays.toString(operations));
    }

    private void registerResponseDTO(Class<?> dtoClass) {
        CrudXResponse annotation = dtoClass.getAnnotation(CrudXResponse.class);
        Class<?> entityClass = annotation.value();
        CrudXOperation[] operations = annotation.operations();

        if (operations.length == 0) {
            // Default: all query operations
            operations = new CrudXOperation[]{
                    CrudXOperation.GET_ID, CrudXOperation.GET_ALL, CrudXOperation.GET_PAGED
            };
        }

        Map<CrudXOperation, Class<?>> opMap = responseMappings.computeIfAbsent(
                entityClass, k -> new ConcurrentHashMap<>());

        for (CrudXOperation op : operations) {
            if (opMap.containsKey(op)) {
                log.warn("⚠️  Duplicate response DTO for {} operation on {} entity: {} (overriding {})",
                        op, entityClass.getSimpleName(), dtoClass.getSimpleName(),
                        opMap.get(op).getSimpleName());
            }
            opMap.put(op, dtoClass);
        }

        log.debug("✓ Response DTO: {} -> {} for operations: {}",
                dtoClass.getSimpleName(), entityClass.getSimpleName(), Arrays.toString(operations));
    }

    /**
     * Get mapper for entity class (lazy load from context if needed)
     */
    @SuppressWarnings("unchecked")
    public <E, R, S> Optional<CrudXMapper<E, R, S>> getMapper(Class<E> entityClass) {
        CrudXMapper<?, ?, ?> mapper = mappers.get(entityClass);

        // Lazy load mapper from context if not cached
        if (mapper == null) {
            String mapperBeanName = getMapperBeanName(entityClass);
            try {
                mapper = applicationContext.getBean(mapperBeanName, CrudXMapper.class);
                mappers.put(entityClass, mapper);
                log.debug("✓ Lazily loaded mapper: {}", mapperBeanName);
            } catch (Exception e) {
                log.debug("Mapper bean {} not found for entity {}",
                        mapperBeanName, entityClass.getSimpleName());
                return Optional.empty();
            }
        }

        return Optional.ofNullable((CrudXMapper<E, R, S>) mapper);
    }

    /**
     * Check if entity has DTO mappings
     */
    public boolean hasDTOMapping(Class<?> entityClass) {
        return requestMappings.containsKey(entityClass) ||
                responseMappings.containsKey(entityClass);
    }

    /**
     * Get all registered entity classes
     */
    public Set<Class<?>> getRegisteredEntities() {
        Set<Class<?>> entities = new HashSet<>();
        entities.addAll(requestMappings.keySet());
        entities.addAll(responseMappings.keySet());
        return entities;
    }

    private String getMapperBeanName(Class<?> entityClass) {
        String name = entityClass.getSimpleName();
        return Character.toLowerCase(name.charAt(0)) + name.substring(1) + "MapperCrudX";
    }

    /**
     * Get statistics for monitoring
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("initialized", initialized);
        stats.put("totalEntities", getRegisteredEntities().size());
        stats.put("totalRequestDTOs", requestMappings.values().stream()
                .mapToInt(Map::size).sum());
        stats.put("totalResponseDTOs", responseMappings.values().stream()
                .mapToInt(Map::size).sum());
        stats.put("cachedMappers", mappers.size());
        return stats;
    }
}