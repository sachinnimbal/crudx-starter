package io.github.sachinnimbal.crudx.core.dto.mapper;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Base interface for all CrudX DTO mappers.
 * Generated mappers implement this interface.
 *
 * @param <E> Entity type
 * @param <R> Request DTO type
 * @param <S> Response DTO type
 * @author Sachin Nimbal
 * @since 1.0.2
 */
public interface CrudXMapper<E, R, S> {

    /**
     * Convert Request DTO to Entity.
     * Used for CREATE operations.
     *
     * @param request the request DTO
     * @return the entity
     */
    E toEntity(R request);

    /**
     * Update existing entity from Request DTO.
     * Used for UPDATE operations.
     *
     * @param request the request DTO with updated fields
     * @param entity the existing entity to update
     */
    void updateEntity(R request, E entity);

    /**
     * Convert Entity to Response DTO.
     * Used for all query operations.
     *
     * @param entity the entity
     * @return the response DTO
     */
    S toResponse(E entity);

    /**
     * Convert list of entities to list of response DTOs.
     *
     * @param entities the entity list
     * @return the response DTO list
     */
    default List<S> toResponseList(List<E> entities) {
        if (entities == null) {
            return null;
        }
        return entities.stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    /**
     * Get the entity class this mapper handles.
     */
    Class<E> getEntityClass();

    /**
     * Get the request DTO class this mapper handles.
     */
    Class<R> getRequestClass();

    /**
     * Get the response DTO class this mapper handles.
     */
    Class<S> getResponseClass();
}