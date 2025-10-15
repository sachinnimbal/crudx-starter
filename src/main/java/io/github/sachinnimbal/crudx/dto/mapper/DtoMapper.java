package io.github.sachinnimbal.crudx.dto.mapper;

import io.github.sachinnimbal.crudx.core.enums.Direction;
import io.github.sachinnimbal.crudx.core.enums.OperationType;
import io.github.sachinnimbal.crudx.dto.generator.DtoMapperGenerator;
import io.github.sachinnimbal.crudx.dto.registry.GeneratedMapperRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * REFACTORED: Now uses generated mappers for optimized performance
 * Falls back to reflection-based mapping if generation fails
 */
@Slf4j
@Component
public class DtoMapper {

    private final DtoMapperGenerator generator;
    private final GeneratedMapperRegistry registry;

    @Autowired
    public DtoMapper(DtoMapperGenerator generator, GeneratedMapperRegistry registry) {
        this.generator = generator;
        this.registry = registry;
    }

    /**
     * Map DTO to Entity (REQUEST direction)
     * Uses generated mapper if available, falls back to reflection
     */
    @SuppressWarnings("unchecked")
    public <E, D> E toEntity(D dto, Class<E> entityClass, OperationType operation) {
        if (dto == null) return null;

        Class<D> dtoClass = (Class<D>) dto.getClass();

        // Try to use generated mapper
        GeneratedMapper<E, D> generatedMapper = registry.findMapper(
                entityClass, dtoClass, operation, Direction.REQUEST
        );

        if (generatedMapper != null) {
            log.trace("Using generated mapper: {}", generatedMapper.getGeneratedClassName());
            return generatedMapper.toEntity(dto);
        }

        // Generate mapper on-demand if not exists
        log.debug("No cached mapper found, generating mapper for {} -> {}",
                dtoClass.getSimpleName(), entityClass.getSimpleName());

        generatedMapper = generator.generateMapper(dtoClass, entityClass, operation, Direction.REQUEST);

        if (generatedMapper != null) {
            registry.register(generatedMapper);
            return generatedMapper.toEntity(dto);
        }

        // Fallback to reflection (slower)
        log.warn("Mapper generation failed, using reflection fallback");
        return toEntityReflection(dto, entityClass);
    }

    /**
     * Map Entity to DTO (RESPONSE direction)
     */
    @SuppressWarnings("unchecked")
    public <E, D> D toDto(E entity, Class<D> dtoClass, OperationType operation) {
        if (entity == null) return null;

        Class<E> entityClass = (Class<E>) entity.getClass();

        // Try to use generated mapper
        GeneratedMapper<E, D> generatedMapper = registry.findMapper(
                entityClass, dtoClass, operation, Direction.RESPONSE
        );

        if (generatedMapper != null) {
            return generatedMapper.toDto(entity);
        }

        // Generate mapper on-demand
        generatedMapper = generator.generateMapper(dtoClass, entityClass, operation, Direction.RESPONSE);

        if (generatedMapper != null) {
            registry.register(generatedMapper);
            return generatedMapper.toDto(entity);
        }

        // Fallback to reflection
        return toDtoReflection(entity, dtoClass);
    }

    /**
     * Batch map DTOs to Entities
     */
    public <E, D> List<E> toEntities(List<D> dtos, Class<E> entityClass, OperationType operation) {
        List<E> entities = new ArrayList<>(dtos.size());
        for (D dto : dtos) {
            entities.add(toEntity(dto, entityClass, operation));
        }
        return entities;
    }

    /**
     * Batch map Entities to DTOs
     */
    public <E, D> List<D> toDtos(List<E> entities, Class<D> dtoClass, OperationType operation) {
        List<D> dtos = new ArrayList<>(entities.size());
        for (E entity : entities) {
            D dto = toDto(entity, dtoClass, operation);
            if (dto != null) {
                dtos.add(dto);
            }
        }
        return dtos;
    }

    // ===== REFLECTION FALLBACKS =====

    private <E, D> E toEntityReflection(D dto, Class<E> entityClass) {
        try {
            E entity = entityClass.getDeclaredConstructor().newInstance();

            for (Field dtoField : dto.getClass().getDeclaredFields()) {
                dtoField.setAccessible(true);
                Object value = dtoField.get(dto);

                if (value != null) {
                    try {
                        Field entityField = getFieldFromClass(entityClass, dtoField.getName());
                        entityField.setAccessible(true);
                        entityField.set(entity, value);
                    } catch (NoSuchFieldException e) {
                        log.trace("Field {} not found in entity", dtoField.getName());
                    }
                }
            }

            return entity;

        } catch (Exception e) {
            log.error("Reflection mapping failed", e);
            throw new RuntimeException("Failed to map DTO to Entity: " + e.getMessage(), e);
        }
    }

    private <E, D> D toDtoReflection(E entity, Class<D> dtoClass) {
        try {
            D dto = dtoClass.getDeclaredConstructor().newInstance();

            for (Field dtoField : dtoClass.getDeclaredFields()) {
                try {
                    Field entityField = getFieldFromClass(entity.getClass(), dtoField.getName());
                    entityField.setAccessible(true);
                    Object value = entityField.get(entity);

                    if (value != null) {
                        dtoField.setAccessible(true);
                        dtoField.set(dto, value);
                    }
                } catch (NoSuchFieldException e) {
                    log.trace("Field {} not found in entity", dtoField.getName());
                }
            }

            return dto;

        } catch (Exception e) {
            log.error("Reflection mapping failed", e);
            return null;
        }
    }

    private Field getFieldFromClass(Class<?> clazz, String fieldName) throws NoSuchFieldException {
        try {
            return clazz.getDeclaredField(fieldName);
        } catch (NoSuchFieldException e) {
            if (clazz.getSuperclass() != null) {
                return getFieldFromClass(clazz.getSuperclass(), fieldName);
            }
            throw e;
        }
    }
}