package io.github.sachinnimbal.crudx.dto.registry;

import io.github.sachinnimbal.crudx.dto.mapper.CrudXEntityMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class CrudXMapperRegistry {

    // Simple: Entity class -> Mapper
    private final Map<Class<?>, CrudXEntityMapper<?, ?>> mappers = new ConcurrentHashMap<>();

    /**
     * Register mapper for entity
     */
    public <E> void register(CrudXEntityMapper<E, ?> mapper) {
        Class<E> entityClass = mapper.getEntityClass();
        mappers.put(entityClass, mapper);

        log.info("✓ Registered unified mapper: {} (supports {} DTOs)",
                entityClass.getSimpleName(),
                mapper.getSupportedDtoClasses().size());
    }

    /**
     * Get mapper for entity
     */
    @SuppressWarnings("unchecked")
    public <E> CrudXEntityMapper<E, ?> getMapper(Class<E> entityClass) {
        return (CrudXEntityMapper<E, ?>) mappers.get(entityClass);
    }

    /**
     * Check if mapper exists for entity
     */
    public boolean hasMapper(Class<?> entityClass) {
        return mappers.containsKey(entityClass);
    }

    /**
     * Get all registered mappers
     */
    public Collection<CrudXEntityMapper<?, ?>> getAllMappers() {
        return mappers.values();
    }

    /**
     * Get statistics
     */
    public MapperStatistics getStatistics() {
        int totalDtos = 0;
        Set<Class<?>> uniqueEntities = new HashSet<>();

        for (CrudXEntityMapper<?, ?> mapper : mappers.values()) {
            uniqueEntities.add(mapper.getEntityClass());
            totalDtos += mapper.getSupportedDtoClasses().size();
        }

        return MapperStatistics.builder()
                .totalMappers(mappers.size())
                .totalEntities(uniqueEntities.size())
                .totalDtos(totalDtos)
                .averageDtosPerEntity(totalDtos / (double) Math.max(1, mappers.size()))
                .build();
    }

    /**
     * Clear all mappers (for testing/regeneration)
     */
    public void clear() {
        mappers.clear();
        log.info("Cleared all mappers");
    }

    @lombok.Data
    @lombok.Builder
    public static class MapperStatistics {
        private int totalMappers;
        private int totalEntities;
        private int totalDtos;
        private double averageDtosPerEntity;
    }
}