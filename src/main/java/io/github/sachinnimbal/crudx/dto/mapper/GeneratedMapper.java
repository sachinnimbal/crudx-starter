package io.github.sachinnimbal.crudx.dto.mapper;

import io.github.sachinnimbal.crudx.dto.metadata.MapperMetadata;

/**
 * Base interface for all generated mappers
 * Similar to MapStruct's Mapper interface
 */
public interface GeneratedMapper<E, D> {

    /**
     * Map DTO to Entity
     */
    E toEntity(D dto);

    /**
     * Map Entity to DTO
     */
    D toDto(E entity);

    /**
     * Update existing entity from DTO
     */
    void updateEntity(D dto, E entity);

    /**
     * Get mapper metadata
     */
    default MapperMetadata getMetadata() {
        return new MapperMetadata(
                getEntityClass(),
                getDtoClass(),
                getGeneratedClassName()
        );
    }

    Class<E> getEntityClass();
    Class<D> getDtoClass();
    String getGeneratedClassName();
}