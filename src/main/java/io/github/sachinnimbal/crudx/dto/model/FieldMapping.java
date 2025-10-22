package io.github.sachinnimbal.crudx.dto.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a field mapping between DTO and Entity.
 *
 * @author CrudX Framework
 * @since 1.0.0
 */
@Data
public class FieldMapping {

    /**
     * DTO field name.
     */
    private String dtoFieldName;

    /**
     * DTO field type (fully qualified).
     */
    private String dtoFieldType;

    /**
     * DTO field simple type.
     */
    private String dtoFieldSimpleType;

    /**
     * Entity field name (source).
     * May differ from dtoFieldName if @CrudXField(source="...") is used.
     */
    private String entityFieldName;

    /**
     * Entity field type (fully qualified).
     */
    private String entityFieldType;

    /**
     * Entity field simple type.
     */
    private String entityFieldSimpleType;

    /**
     * Whether field should be ignored.
     */
    private boolean ignored;

    /**
     * Whether field is a nested object.
     */
    private boolean nested;

    /**
     * Nested DTO class (if nested).
     */
    private String nestedDTOClass;

    /**
     * Max recursion depth (for nested).
     */
    private int maxDepth = 3;

    /**
     * Date/Number format pattern.
     */
    private String format;

    /**
     * Custom mapping expression.
     */
    private String expression;

    /**
     * Default value if source is null.
     */
    private String defaultValue;

    /**
     * Whether field is a collection (List, Set, Map).
     */
    private boolean isCollection;

    /**
     * Collection type (List, Set, Map).
     */
    private String collectionType;

    /**
     * Generic type for collections.
     */
    private String genericType;

    /**
     * Whether field is primitive.
     */
    private boolean isPrimitive;

    /**
     * Whether field is enum.
     */
    private boolean isEnum;

    /**
     * Whether field is date/time.
     */
    private boolean isDate;

    /**
     * Whether field is numeric.
     */
    private boolean isNumeric;

    /**
     * Validation annotations to inherit.
     */
    private List<EntityMetadata.ValidationMetadata> inheritedValidations = new ArrayList<>();

    /**
     * Whether types are compatible (same or convertible).
     */
    private boolean typesCompatible;

    /**
     * Type conversion needed (e.g., String → Long).
     */
    private String typeConversion;

    // Helper methods

    public boolean requiresFormatting() {
        return format != null && !format.isEmpty();
    }

    public boolean hasExpression() {
        return expression != null && !expression.isEmpty();
    }

    public boolean hasDefaultValue() {
        return defaultValue != null && !defaultValue.isEmpty();
    }

    public boolean requiresValidation() {
        return inheritedValidations != null && !inheritedValidations.isEmpty();
    }

    public boolean needsTypeConversion() {
        return typeConversion != null && !typeConversion.isEmpty();
    }
}