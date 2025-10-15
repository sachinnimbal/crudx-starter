package io.github.sachinnimbal.crudx.dto.metadata;

import io.github.sachinnimbal.crudx.core.enums.Direction;
import io.github.sachinnimbal.crudx.core.enums.OperationType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class MapperMetadata {
    private Class<?> entityClass;
    private Class<?> dtoClass;
    private OperationType operation;
    private Direction direction;

    private String generatedClassName;
    private LocalDateTime generatedAt;
    private String generatorVersion;

    // Field mapping info
    private List<FieldMappingInfo> fieldMappings;
    private Set<String> ignoredFields;
    private Set<String> nestedFields;

    // Performance stats
    private boolean optimized;
    private String optimizationStrategy;

    public MapperMetadata(Class<?> entityClass, Class<?> dtoClass, String generatedClassName) {
        this.entityClass = entityClass;
        this.dtoClass = dtoClass;
        this.generatedClassName = generatedClassName;
        this.generatedAt = LocalDateTime.now();
        this.generatorVersion = "3.0.0";
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class FieldMappingInfo {
        private String sourceField;
        private String targetField;
        private String mappingType; // DIRECT, NESTED, COLLECTION, COMPUTED
        private boolean requiresValidation;
    }
}