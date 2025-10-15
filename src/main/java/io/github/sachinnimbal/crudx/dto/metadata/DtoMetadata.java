package io.github.sachinnimbal.crudx.dto.metadata;

import io.github.sachinnimbal.crudx.core.enums.Direction;
import io.github.sachinnimbal.crudx.core.enums.OperationType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class DtoMetadata {
    private Class<?> dtoClass;
    private Class<?> entityClass;
    private OperationType operation;
    private Direction direction;

    // Inherited from entity
    private Set<String> immutableFields;
    private List<UniqueConstraintMetadata> uniqueConstraints;
    private Map<String, ValidationProfile> validationProfiles;

    // Field mappings
    private Map<String, FieldMapping> fieldMappings;

    // Pre-compiled mapper instance
    private Object mapper;

    // Performance metadata
    private int[] fieldOffsets;
    private boolean useUnsafe;
}