package io.github.sachinnimbal.crudx.dto.registry;

import io.github.sachinnimbal.crudx.core.enums.Direction;
import io.github.sachinnimbal.crudx.core.enums.OperationType;
import io.github.sachinnimbal.crudx.dto.mapper.GeneratedMapper;
import io.github.sachinnimbal.crudx.dto.metadata.MapperMetadata;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simplified registry that stores ONE mapper per Entity-DTO pair
 * (no longer needs operation/direction in lookup key)
 */
@Slf4j
@Component
public class GeneratedMapperRegistry {

    // Simplified: One mapper per entity-DTO pair
    private final Map<String, GeneratedMapper<?, ?>> mappers = new ConcurrentHashMap<>();
    private final Map<String, MapperMetadata> metadata = new ConcurrentHashMap<>();

    /**
     * Register a generated mapper (unified for all operations)
     */
    public <E, D> void register(GeneratedMapper<E, D> mapper) {
        String key = createSimpleKey(
                mapper.getEntityClass(),
                mapper.getDtoClass()
        );

        mappers.put(key, mapper);

        // Create metadata
        MapperMetadata meta = MapperMetadata.builder()
                .entityClass(mapper.getEntityClass())
                .dtoClass(mapper.getDtoClass())
                .generatedClassName(mapper.getGeneratedClassName())
                .optimized(true)
                .build();

        metadata.put(key, meta);

        log.debug("✓ Registered unified mapper: {} <-> {}",
                mapper.getEntityClass().getSimpleName(),
                mapper.getDtoClass().getSimpleName());
    }

    /**
     * Find mapper by entity and DTO class (operation/direction no longer needed)
     * This works for ALL operations since we have a unified mapper
     */
    @SuppressWarnings("unchecked")
    public <E, D> GeneratedMapper<E, D> findMapper(
            Class<E> entityClass,
            Class<D> dtoClass,
            OperationType operation,  // Ignored - kept for backward compatibility
            Direction direction) {    // Ignored - kept for backward compatibility

        String key = createSimpleKey(entityClass, dtoClass);
        return (GeneratedMapper<E, D>) mappers.get(key);
    }

    /**
     * Simplified lookup - no operation/direction needed
     */
    @SuppressWarnings("unchecked")
    public <E, D> GeneratedMapper<E, D> findMapper(
            Class<E> entityClass,
            Class<D> dtoClass) {

        String key = createSimpleKey(entityClass, dtoClass);
        return (GeneratedMapper<E, D>) mappers.get(key);
    }

    /**
     * Get all registered mappers
     */
    public List<MapperMetadata> getAllMappers() {
        return new ArrayList<>(metadata.values());
    }

    /**
     * Get mappers for specific entity
     */
    public List<MapperMetadata> getMappersForEntity(Class<?> entityClass) {
        return metadata.values().stream()
                .filter(m -> m.getEntityClass().equals(entityClass))
                .toList();
    }

    /**
     * Get mapper statistics
     */
    public MapperStatistics getStatistics() {
        return MapperStatistics.builder()
                .totalMappers(mappers.size())
                .optimizedMappers((int) metadata.values().stream()
                        .filter(MapperMetadata::isOptimized)
                        .count())
                .entities(metadata.values().stream()
                        .map(MapperMetadata::getEntityClass)
                        .distinct()
                        .count())
                .dtos(metadata.values().stream()
                        .map(MapperMetadata::getDtoClass)
                        .distinct()
                        .count())
                .build();
    }

    /**
     * Check if mapper exists (simplified)
     */
    public boolean hasMapper(Class<?> entityClass, Class<?> dtoClass) {
        String key = createSimpleKey(entityClass, dtoClass);
        return mappers.containsKey(key);
    }

    /**
     * Get mapper by classes (convenient method)
     */
    @SuppressWarnings("unchecked")
    public <E, D> GeneratedMapper<E, D> getMapper(Class<E> entityClass, Class<D> dtoClass) {
        return findMapper(entityClass, dtoClass);
    }

    /**
     * Clear all mappers (for testing)
     */
    public void clear() {
        mappers.clear();
        metadata.clear();
        log.info("Cleared all registered mappers");
    }

    /**
     * Simplified key: just entity + DTO (no operation/direction)
     */
    private String createSimpleKey(Class<?> entityClass, Class<?> dtoClass) {
        return entityClass.getName() + "_" + dtoClass.getName();
    }

    @Data
    @Builder
    public static class MapperStatistics {
        private int totalMappers;
        private int optimizedMappers;
        private long entities;
        private long dtos;
    }
}