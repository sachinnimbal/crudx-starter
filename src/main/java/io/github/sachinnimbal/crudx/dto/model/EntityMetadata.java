package io.github.sachinnimbal.crudx.dto.model;

import io.github.sachinnimbal.crudx.core.enums.DatabaseType;
import lombok.Data;

import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import java.util.*;

/**
 * Metadata extracted from entity class at compile-time.
 *
 * @author CrudX Framework
 * @since 1.0.0
 */
@Data
public class EntityMetadata {

    /**
     * Fully qualified entity class name.
     * Example: "com.example.domain.User"
     */
    private String entityClassName;

    /**
     * Simple entity class name.
     * Example: "User"
     */
    private String entitySimpleName;

    /**
     * Entity package name.
     * Example: "com.example.domain"
     */
    private String entityPackage;

    /**
     * ID type class name.
     * Example: "Long", "String", "ObjectId"
     */
    private String idType;

    /**
     * Database type (MySQL, PostgreSQL, MongoDB).
     */
    private DatabaseType databaseType;

    /**
     * Original TypeElement from annotation processing.
     */
    private TypeElement entityElement;

    /**
     * All fields from entity.
     * Key: field name
     * Value: field metadata
     */
    private Map<String, FieldMetadata> fields = new LinkedHashMap<>();

    /**
     * Fields marked with @CrudXImmutable.
     */
    private Set<String> immutableFields = new HashSet<>();

    /**
     * Audit fields (createdAt, updatedAt, createdBy, updatedBy).
     */
    private Set<String> auditFields = new HashSet<>();

    /**
     * Fields with validation annotations (@Email, @NotNull, etc.).
     */
    private Map<String, List<ValidationMetadata>> validationMap = new HashMap<>();

    /**
     * Nested entity relationships (@OneToMany, @ManyToOne, etc.).
     */
    private Map<String, RelationshipMetadata> relationships = new HashMap<>();

    /**
     * All DTOs mapped to this entity.
     */
    private List<DTOMetadata> mappedDTOs = new ArrayList<>();

    /**
     * Generated mapper class name.
     * Example: "UserMapperCrudX"
     */
    private String mapperClassName;

    /**
     * Generated mapper package.
     */
    private String mapperPackage;

    /**
     * Whether entity has unique constraints.
     */
    private boolean hasUniqueConstraints;

    /**
     * Unique constraint definitions.
     */
    private List<UniqueConstraintMetadata> uniqueConstraints = new ArrayList<>();

    // Helper methods

    public String getMapperFullyQualifiedName() {
        return mapperPackage + "." + mapperClassName;
    }

    public boolean isImmutable(String fieldName) {
        return immutableFields.contains(fieldName);
    }

    public boolean isAuditField(String fieldName) {
        return auditFields.contains(fieldName);
    }

    public boolean hasValidations(String fieldName) {
        return validationMap.containsKey(fieldName) &&
                !validationMap.get(fieldName).isEmpty();
    }

    public List<ValidationMetadata> getValidations(String fieldName) {
        return validationMap.getOrDefault(fieldName, Collections.emptyList());
    }

    public boolean isRelationship(String fieldName) {
        return relationships.containsKey(fieldName);
    }

    public RelationshipMetadata getRelationship(String fieldName) {
        return relationships.get(fieldName);
    }

    /**
     * Field metadata.
     */
    @Data
    public static class FieldMetadata {
        private String name;
        private String type; // Fully qualified type name
        private String simpleType; // Simple type name
        private boolean isCollection;
        private String collectionType; // List, Set, Map
        private String genericType; // For collections
        private VariableElement element;
        private boolean isPrimitive;
        private boolean isEnum;
        private boolean isDate;
        private boolean isNumeric;
    }

    /**
     * Validation annotation metadata.
     */
    @Data
    public static class ValidationMetadata {
        private String annotationType; // @NotNull, @Email, etc.
        private Map<String, Object> attributes; // min, max, message, etc.
        private String fullAnnotation; // Complete annotation string
    }

    /**
     * Relationship metadata.
     */
    @Data
    public static class RelationshipMetadata {
        private String type; // OneToMany, ManyToOne, etc.
        private String targetEntity;
        private boolean isBidirectional;
        private String mappedBy;
        private String fetchType; // LAZY, EAGER
    }

    /**
     * Unique constraint metadata.
     */
    @Data
    public static class UniqueConstraintMetadata {
        private String[] fields;
        private String name;
        private String message;
    }
}