package io.github.sachinnimbal.crudx.dto.metadata;

import io.github.sachinnimbal.crudx.core.enums.Direction;
import io.github.sachinnimbal.crudx.core.enums.OperationType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Objects;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class EndpointDescriptor {
    private Class<?> entityClass;
    private OperationType operation;
    private Direction direction;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EndpointDescriptor that)) return false;
        return Objects.equals(entityClass, that.entityClass) &&
                operation == that.operation &&
                direction == that.direction;
    }

    @Override
    public int hashCode() {
        return Objects.hash(entityClass, operation, direction);
    }
}
