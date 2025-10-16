package io.github.sachinnimbal.crudx.dto.mapper;

import io.github.sachinnimbal.crudx.dto.registry.CrudXMapperRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Simplified DTO Mapper - Uses generated mappers
 * No operation type needed - mapper handles all DTOs for an entity
 */
@Slf4j
@Component
public class CrudXDtoMapper {

    @Autowired
    private CrudXMapperRegistry mapperRegistry;

    // Performance statistics
    private final Map<String, MappingStats> mappingStats = new ConcurrentHashMap<>();

    /**
     * Map DTO to Entity
     */
    @SuppressWarnings("unchecked")
    public <E, D> E toEntity(D dto, Class<E> entityClass) {
        if (dto == null) return null;

        long startTime = System.nanoTime();
        String statsKey = getStatsKey(entityClass, dto.getClass(), "toEntity");

        try {
            CrudXEntityMapper<E, Object> mapper =
                    (CrudXEntityMapper<E, Object>) mapperRegistry.getMapper(entityClass);

            if (mapper == null) {
                log.warn("No mapper found for entity: {}", entityClass.getSimpleName());
                recordMapping(statsKey, System.nanoTime() - startTime, false);
                return null;
            }

            E result = mapper.toEntity(dto);
            recordMapping(statsKey, System.nanoTime() - startTime, true);
            return result;

        } catch (Exception e) {
            recordMapping(statsKey, System.nanoTime() - startTime, false);
            log.error("Failed to map DTO {} to entity {}: {}",
                    dto.getClass().getSimpleName(),
                    entityClass.getSimpleName(),
                    e.getMessage());
            throw e;
        }
    }

    /**
     * Map Entity to specific DTO class
     */
    @SuppressWarnings("unchecked")
    public <E, D> D toDto(E entity, Class<E> entityClass, Class<D> dtoClass) {
        if (entity == null) return null;

        long startTime = System.nanoTime();
        String statsKey = getStatsKey(entityClass, dtoClass, "toDto");

        try {
            CrudXEntityMapper<E, Object> mapper =
                    (CrudXEntityMapper<E, Object>) mapperRegistry.getMapper(entityClass);

            if (mapper == null) {
                log.warn("No mapper found for entity: {}", entityClass.getSimpleName());
                recordMapping(statsKey, System.nanoTime() - startTime, false);
                return null;
            }

            D result = mapper.toDto(entity, dtoClass);
            recordMapping(statsKey, System.nanoTime() - startTime, true);
            return result;

        } catch (Exception e) {
            recordMapping(statsKey, System.nanoTime() - startTime, false);
            log.error("Failed to map entity {} to DTO {}: {}",
                    entityClass.getSimpleName(),
                    dtoClass.getSimpleName(),
                    e.getMessage());
            throw e;
        }
    }

    /**
     * Batch map DTOs to Entities
     */
    @SuppressWarnings("unchecked")
    public <E, D> List<E> toEntities(List<D> dtos, Class<E> entityClass) {
        if (dtos == null || dtos.isEmpty()) return List.of();

        long startTime = System.nanoTime();
        String statsKey = getStatsKey(entityClass, Object.class, "toEntities-batch");

        try {
            CrudXEntityMapper<E, Object> mapper =
                    (CrudXEntityMapper<E, Object>) mapperRegistry.getMapper(entityClass);

            if (mapper == null) {
                log.warn("No mapper found for entity: {}", entityClass.getSimpleName());
                recordMapping(statsKey, System.nanoTime() - startTime, false);
                return List.of();
            }

            List<E> result = mapper.toEntities((List<Object>) dtos);
            recordBatchMapping(statsKey, System.nanoTime() - startTime, dtos.size(), true);
            return result;

        } catch (Exception e) {
            recordBatchMapping(statsKey, System.nanoTime() - startTime, dtos.size(), false);
            log.error("Failed to batch map DTOs to entities {}: {}",
                    entityClass.getSimpleName(), e.getMessage());
            throw e;
        }
    }

    /**
     * Batch map Entities to specific DTO class
     */
    @SuppressWarnings("unchecked")
    public <E, D> List<D> toDtos(List<E> entities, Class<E> entityClass, Class<D> dtoClass) {
        if (entities == null || entities.isEmpty()) return List.of();

        long startTime = System.nanoTime();
        String statsKey = getStatsKey(entityClass, dtoClass, "toDtos-batch");

        try {
            CrudXEntityMapper<E, Object> mapper =
                    (CrudXEntityMapper<E, Object>) mapperRegistry.getMapper(entityClass);

            if (mapper == null) {
                log.warn("No mapper found for entity: {}", entityClass.getSimpleName());
                recordBatchMapping(statsKey, System.nanoTime() - startTime, entities.size(), false);
                return List.of();
            }

            List<D> result = mapper.toDtos(entities, dtoClass);
            recordBatchMapping(statsKey, System.nanoTime() - startTime, entities.size(), true);
            return result;

        } catch (Exception e) {
            recordBatchMapping(statsKey, System.nanoTime() - startTime, entities.size(), false);
            log.error("Failed to batch map entities {} to DTOs {}: {}",
                    entityClass.getSimpleName(),
                    dtoClass.getSimpleName(),
                    e.getMessage());
            throw e;
        }
    }

    /**
     * Update entity from DTO (partial update)
     */
    @SuppressWarnings("unchecked")
    public <E, D> void updateEntity(D dto, E entity, Class<E> entityClass) {
        if (dto == null || entity == null) return;

        long startTime = System.nanoTime();
        String statsKey = getStatsKey(entityClass, dto.getClass(), "updateEntity");

        try {
            CrudXEntityMapper<E, Object> mapper =
                    (CrudXEntityMapper<E, Object>) mapperRegistry.getMapper(entityClass);

            if (mapper == null) {
                log.warn("No mapper found for entity: {}", entityClass.getSimpleName());
                recordMapping(statsKey, System.nanoTime() - startTime, false);
                return;
            }

            mapper.updateEntity(dto, entity);
            recordMapping(statsKey, System.nanoTime() - startTime, true);

        } catch (Exception e) {
            recordMapping(statsKey, System.nanoTime() - startTime, false);
            log.error("Failed to update entity {} from DTO {}: {}",
                    entityClass.getSimpleName(),
                    dto.getClass().getSimpleName(),
                    e.getMessage());
            throw e;
        }
    }

    // ===== STATISTICS METHODS =====

    private String getStatsKey(Class<?> entityClass, Class<?> dtoClass, String operation) {
        return String.format("%s -> %s [%s]",
                entityClass.getSimpleName(),
                dtoClass.getSimpleName(),
                operation);
    }

    private void recordMapping(String key, long durationNanos, boolean success) {
        MappingStats stats = mappingStats.computeIfAbsent(key, k -> new MappingStats());
        stats.recordMapping(durationNanos, success);
    }

    private void recordBatchMapping(String key, long durationNanos, int count, boolean success) {
        MappingStats stats = mappingStats.computeIfAbsent(key, k -> new MappingStats());
        stats.recordBatchMapping(durationNanos, count, success);
    }

    public Map<String, MappingStats> getMappingStats() {
        return new ConcurrentHashMap<>(mappingStats);
    }

    public void clearStats() {
        mappingStats.clear();
        log.info("Cleared DTO mapping statistics");
    }

    /**
     * DTO Mapping Statistics
     */
    public static class MappingStats {
        private final AtomicInteger totalMappings = new AtomicInteger(0);
        private final AtomicInteger successfulMappings = new AtomicInteger(0);
        private final AtomicInteger failedMappings = new AtomicInteger(0);
        private final AtomicLong totalDurationNanos = new AtomicLong(0);
        private final AtomicLong minDurationNanos = new AtomicLong(Long.MAX_VALUE);
        private final AtomicLong maxDurationNanos = new AtomicLong(0);
        private final AtomicInteger totalRecordsMapped = new AtomicInteger(0);

        public void recordMapping(long durationNanos, boolean success) {
            totalMappings.incrementAndGet();
            totalDurationNanos.addAndGet(durationNanos);
            totalRecordsMapped.incrementAndGet();

            if (success) {
                successfulMappings.incrementAndGet();
            } else {
                failedMappings.incrementAndGet();
            }

            updateMinMax(durationNanos);
        }

        public void recordBatchMapping(long durationNanos, int count, boolean success) {
            totalMappings.incrementAndGet();
            totalDurationNanos.addAndGet(durationNanos);
            totalRecordsMapped.addAndGet(count);

            if (success) {
                successfulMappings.incrementAndGet();
            } else {
                failedMappings.incrementAndGet();
            }

            updateMinMax(durationNanos);
        }

        private void updateMinMax(long durationNanos) {
            minDurationNanos.updateAndGet(current -> Math.min(current, durationNanos));
            maxDurationNanos.updateAndGet(current -> Math.max(current, durationNanos));
        }

        public int getTotalMappings() {
            return totalMappings.get();
        }

        public int getSuccessfulMappings() {
            return successfulMappings.get();
        }

        public int getFailedMappings() {
            return failedMappings.get();
        }

        public long getTotalDurationMs() {
            return totalDurationNanos.get() / 1_000_000;
        }

        public double getAverageDurationMs() {
            int count = totalMappings.get();
            return count > 0 ? (totalDurationNanos.get() / 1_000_000.0) / count : 0;
        }

        public long getMinDurationMs() {
            long min = minDurationNanos.get();
            return min == Long.MAX_VALUE ? 0 : min / 1_000_000;
        }

        public long getMaxDurationMs() {
            return maxDurationNanos.get() / 1_000_000;
        }

        public int getTotalRecordsMapped() {
            return totalRecordsMapped.get();
        }

        public double getSuccessRate() {
            int total = totalMappings.get();
            return total > 0 ? (successfulMappings.get() * 100.0) / total : 0;
        }
    }
}