package io.github.sachinnimbal.crudx.dto.model;

import io.github.sachinnimbal.crudx.dto.enums.CrudOperation;
import lombok.Data;

import javax.lang.model.element.TypeElement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Metadata extracted from DTO class at compile-time.
 *
 * @author CrudX Framework
 * @since 1.0.0
 */
@Data
public class DTOMetadata {

    /**
     * DTO type (REQUEST or RESPONSE).
     */
    private DTOType type;

    /**
     * Fully qualified DTO class name.
     * Example: "com.example.dto.UserCreateRequest"
     */
    private String dtoClassName;

    /**
     * Simple DTO class name.
     */
    private String dtoSimpleName;

    /**
     * DTO package name.
     */
    private String dtoPackage;

    /**
     * Entity this DTO maps to/from.
     */
    private EntityMetadata entityMetadata;

    /**
     * Operations this DTO applies to.
     */
    private Set<CrudOperation> operations = new HashSet<>();

    /**
     * Original TypeElement from annotation processing.
     */
    private TypeElement dtoElement;

    /**
     * Field mappings (DTO field → Entity field).
     */
    private List<FieldMapping> fieldMappings = new ArrayList<>();

    /**
     * Whether to inherit validations from entity.
     */
    private boolean inheritValidations;

    /**
     * Whether to exclude immutable fields.
     */
    private boolean excludeImmutable;

    /**
     * Whether to exclude audit fields.
     */
    private boolean excludeAuditFields;

    /**
     * Whether to include nested objects.
     */
    private boolean includeNested;

    // Helper methods

    public boolean appliesToOperation(CrudOperation operation) {
        return operations.isEmpty() || operations.contains(operation);
    }

    public boolean isForAllOperations() {
        return operations.isEmpty();
    }

    public FieldMapping getFieldMapping(String dtoFieldName) {
        return fieldMappings.stream()
                .filter(m -> m.getDtoFieldName().equals(dtoFieldName))
                .findFirst()
                .orElse(null);
    }

    public boolean hasNestedFields() {
        return fieldMappings.stream().anyMatch(FieldMapping::isNested);
    }

    public List<FieldMapping> getNestedFields() {
        return fieldMappings.stream()
                .filter(FieldMapping::isNested)
                .toList();
    }

    public String getMapperMethodName() {
        return switch (type) {
            case REQUEST -> operations.contains(CrudOperation.UPDATE)
                    ? "updateEntity"
                    : "toEntity";
            case RESPONSE -> "toResponse";
        };
    }

    public enum DTOType {
        REQUEST,  // For @CrudXRequest
        RESPONSE  // For @CrudXResponse
    }
}