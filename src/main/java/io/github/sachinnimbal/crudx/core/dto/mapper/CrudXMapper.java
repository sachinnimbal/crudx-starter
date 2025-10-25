package io.github.sachinnimbal.crudx.core.dto.mapper;

import java.util.List;
import java.util.stream.Collectors;

public interface CrudXMapper<E, R, S> {

    E toEntity(R request);

    void updateEntity(R request, E entity);

    S toResponse(E entity);

    default List<S> toResponseList(List<E> entities) {
        if (entities == null) {
            return null;
        }
        return entities.stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    Class<E> getEntityClass();

    Class<R> getRequestClass();

    Class<S> getResponseClass();
}
