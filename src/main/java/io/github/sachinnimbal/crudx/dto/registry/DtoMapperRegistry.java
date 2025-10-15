package io.github.sachinnimbal.crudx.dto.registry;

import io.github.sachinnimbal.crudx.core.enums.Direction;
import io.github.sachinnimbal.crudx.core.enums.OperationType;
import io.github.sachinnimbal.crudx.dto.mapper.GeneratedMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central registry for pre-generated DTO mappers
 * Mappers are generated at startup and stored for O(1) access
 */
@Slf4j
@Component
public class DtoMapperRegistry {

    // Store generated mapper instances (not metadata)
    private final Map<MapperKey, GeneratedMapper<?, ?>> mappers = new ConcurrentHashMap<>();

    /**
     * Register a pre-generated mapper
     */
    public <E, D> void registerMapper(Class<E> entityClass,
                                      Class<D> dtoClass,
                                      OperationType operation,
                                      Direction direction,
                                      GeneratedMapper<E, D> mapper) {
        MapperKey key = MapperKey.of(entityClass, dtoClass, operation, direction);
        mappers.put(key, mapper);
        log.debug("Registered mapper: {} -> {} for {} ({})",
                entityClass.getSimpleName(),
                dtoClass.getSimpleName(),
                operation,
                direction);
    }

    /**
     * Get mapper for entity-dto pair
     */
    @SuppressWarnings("unchecked")
    public <E, D> GeneratedMapper<E, D> getMapper(Class<E> entityClass,
                                                  Class<D> dtoClass,
                                                  OperationType operation,
                                                  Direction direction) {
        MapperKey key = MapperKey.of(entityClass, dtoClass, operation, direction);
        return (GeneratedMapper<E, D>) mappers.get(key);
    }

    /**
     * Get mapper by entity class (for backward compatibility)
     */
    @SuppressWarnings("unchecked")
    public <E, D> GeneratedMapper<E, D> getMapperByEntity(Class<E> entityClass,
                                                          OperationType operation,
                                                          Direction direction) {
        for (Map.Entry<MapperKey, GeneratedMapper<?, ?>> entry : mappers.entrySet()) {
            MapperKey key = entry.getKey();
            if (key.entityClass.equals(entityClass)
                    && key.operation == operation
                    && key.direction == direction) {
                return (GeneratedMapper<E, D>) entry.getValue();
            }
        }
        return null;
    }

    /**
     * Check if mapper exists
     */
    public boolean hasMapper(Class<?> entityClass,
                             Class<?> dtoClass,
                             OperationType operation,
                             Direction direction) {
        MapperKey key = MapperKey.of(entityClass, dtoClass, operation, direction);
        return mappers.containsKey(key);
    }

    /**
     * Get all registered mappers count
     */
    public int getMapperCount() {
        return mappers.size();
    }

    /**
     * Composite key for mapper lookup
     */
    private static class MapperKey {
        private final Class<?> entityClass;
        private final Class<?> dtoClass;
        private final OperationType operation;
        private final Direction direction;

        private MapperKey(Class<?> entityClass,
                          Class<?> dtoClass,
                          OperationType operation,
                          Direction direction) {
            this.entityClass = entityClass;
            this.dtoClass = dtoClass;
            this.operation = operation;
            this.direction = direction;
        }

        static MapperKey of(Class<?> entityClass,
                            Class<?> dtoClass,
                            OperationType operation,
                            Direction direction) {
            return new MapperKey(entityClass, dtoClass, operation, direction);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof MapperKey)) return false;
            MapperKey that = (MapperKey) o;
            return entityClass.equals(that.entityClass) &&
                    dtoClass.equals(that.dtoClass) &&
                    operation == that.operation &&
                    direction == that.direction;
        }

        @Override
        public int hashCode() {
            int result = entityClass.hashCode();
            result = 31 * result + dtoClass.hashCode();
            result = 31 * result + operation.hashCode();
            result = 31 * result + direction.hashCode();
            return result;
        }
    }
}