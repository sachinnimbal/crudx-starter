package io.github.sachinnimbal.crudx.dto.mapper;

import java.util.List;

/**
 * UNIFIED MAPPER INTERFACE
 * <p>
 * One mapper per entity handles ALL DTO variants.
 * Runtime DTO type detection enables flexible mapping.
 *
 * @param <E> Entity type
 * @param <D> Base DTO type (usually Object for flexibility)
 */
public interface CrudXEntityMapper<E, D> {

    /**
     * Convert any DTO to Entity
     * Mapper detects DTO type at runtime
     */
    E toEntity(D dto);

    /**
     * Convert Entity to specific DTO type
     *
     * @param entity   The entity to convert
     * @param dtoClass Target DTO class (e.g., UserResponse.class)
     */
    <T> T toDto(E entity, Class<T> dtoClass);

    /**
     * Update existing entity from DTO (partial update)
     */
    void updateEntity(D dto, E entity);

    /**
     * Batch convert entities to DTOs
     */
    <T> List<T> toDtos(List<E> entities, Class<T> dtoClass);

    /**
     * Batch convert DTOs to entities
     */
    List<E> toEntities(List<D> dtos);

    /**
     * Get entity class this mapper handles
     */
    Class<E> getEntityClass();

    /**
     * Get all DTO classes this mapper supports
     */
    List<Class<?>> getSupportedDtoClasses();
}