package io.github.sachinnimbal.crudx.dto.service;

import io.github.sachinnimbal.crudx.core.enums.Direction;
import io.github.sachinnimbal.crudx.core.enums.OperationType;
import io.github.sachinnimbal.crudx.dto.generator.DtoMapperGenerator;
import io.github.sachinnimbal.crudx.dto.mapper.DtoMapper;
import io.github.sachinnimbal.crudx.dto.mapper.GeneratedMapper;
import io.github.sachinnimbal.crudx.dto.registry.DtoRegistry;
import io.github.sachinnimbal.crudx.dto.registry.GeneratedMapperRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * NEW: Standalone DTO Mapping Service
 * Can be used independently without CrudXController
 * <p>
 * Usage:
 *
 * @Autowired private CrudXDtoMappingService dtoService;
 * <p>
 * UserDto dto = dtoService.toDto(user, UserDto.class);
 * User entity = dtoService.toEntity(dto, User.class);
 */
@Slf4j
@Service
public class CrudXDtoMappingService {

    @Autowired
    private DtoMapper dtoMapper;

    @Autowired
    private DtoMapperGenerator mapperGenerator;

    @Autowired
    private GeneratedMapperRegistry mapperRegistry;

    @Autowired
    private DtoRegistry dtoRegistry;

    /**
     * Map Entity to DTO (most common use case)
     * Auto-detects operation type based on context
     */
    public <E, D> D toDto(E entity, Class<D> dtoClass) {
        return toDto(entity, dtoClass, OperationType.GET_BY_ID);
    }

    /**
     * Map Entity to DTO with specific operation
     */
    public <E, D> D toDto(E entity, Class<D> dtoClass, OperationType operation) {
        if (entity == null) return null;
        return dtoMapper.toDto(entity, dtoClass, operation);
    }

    /**
     * Map DTO to Entity (most common use case)
     */
    public <D, E> E toEntity(D dto, Class<E> entityClass) {
        return toEntity(dto, entityClass, OperationType.CREATE);
    }

    /**
     * Map DTO to Entity with specific operation
     */
    public <D, E> E toEntity(D dto, Class<E> entityClass, OperationType operation) {
        if (dto == null) return null;
        return dtoMapper.toEntity(dto, entityClass, operation);
    }

    /**
     * Batch map Entities to DTOs
     */
    public <E, D> List<D> toDtos(List<E> entities, Class<D> dtoClass) {
        return toDtos(entities, dtoClass, OperationType.GET_ALL);
    }

    /**
     * Batch map Entities to DTOs with operation
     */
    public <E, D> List<D> toDtos(List<E> entities, Class<D> dtoClass, OperationType operation) {
        if (entities == null || entities.isEmpty()) {
            return List.of();
        }
        return dtoMapper.toDtos(entities, dtoClass, operation);
    }

    /**
     * Batch map DTOs to Entities
     */
    public <D, E> List<E> toEntities(List<D> dtos, Class<E> entityClass) {
        return toEntities(dtos, entityClass, OperationType.CREATE);
    }

    /**
     * Batch map DTOs to Entities with operation
     */
    public <D, E> List<E> toEntities(List<D> dtos, Class<E> entityClass, OperationType operation) {
        if (dtos == null || dtos.isEmpty()) {
            return List.of();
        }
        return dtoMapper.toEntities(dtos, entityClass, operation);
    }

    /**
     * Get or generate mapper for specific DTO-Entity pair
     * Useful for custom mapping scenarios
     */
    public <E, D> GeneratedMapper<E, D> getMapper(
            Class<E> entityClass,
            Class<D> dtoClass,
            OperationType operation,
            Direction direction) {

        // Try to find existing mapper
        GeneratedMapper<E, D> mapper = mapperRegistry.findMapper(
                entityClass, dtoClass, operation, direction
        );

        if (mapper != null) {
            return mapper;
        }

        // Generate on-demand
        log.debug("Generating mapper on-demand: {} -> {} ({})",
                dtoClass.getSimpleName(), entityClass.getSimpleName(), operation);

        mapper = mapperGenerator.generateMapper(dtoClass, entityClass, operation, direction);

        if (mapper != null) {
            mapperRegistry.register(mapper);
        }

        return mapper;
    }

    /**
     * Check if mapper exists for DTO-Entity pair
     */
    public boolean hasMapper(Class<?> entityClass, Class<?> dtoClass,
                             OperationType operation, Direction direction) {
        return mapperRegistry.findMapper(entityClass, dtoClass, operation, direction) != null;
    }

    /**
     * Pre-generate mapper for future use (optimization)
     */
    public <E, D> void preGenerateMapper(
            Class<E> entityClass,
            Class<D> dtoClass,
            OperationType operation,
            Direction direction) {

        if (!hasMapper(entityClass, dtoClass, operation, direction)) {
            log.info("Pre-generating mapper: {} -> {} ({})",
                    dtoClass.getSimpleName(), entityClass.getSimpleName(), operation);

            GeneratedMapper<E, D> mapper = mapperGenerator.generateMapper(
                    dtoClass, entityClass, operation, direction
            );

            if (mapper != null) {
                mapperRegistry.register(mapper);
            }
        } else {
            log.debug("Mapper already exists: {} -> {}",
                    dtoClass.getSimpleName(), entityClass.getSimpleName());
        }
    }

    /**
     * Check if DTO is registered
     */
    public boolean isDtoRegistered(Class<?> dtoClass) {
        return dtoRegistry.getAllDtos().contains(dtoClass);
    }

    /**
     * Get all registered DTOs
     */
    public List<Class<?>> getAllRegisteredDtos() {
        return new ArrayList<>(dtoRegistry.getAllDtos());
    }
}