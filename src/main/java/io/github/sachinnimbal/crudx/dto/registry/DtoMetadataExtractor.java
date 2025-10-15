package io.github.sachinnimbal.crudx.dto.registry;

import io.github.sachinnimbal.crudx.core.annotations.CrudXImmutable;
import io.github.sachinnimbal.crudx.core.annotations.CrudXUniqueConstraint;
import io.github.sachinnimbal.crudx.core.annotations.dto.*;
import io.github.sachinnimbal.crudx.core.enums.Direction;
import io.github.sachinnimbal.crudx.core.enums.OperationType;
import io.github.sachinnimbal.crudx.dto.metadata.*;
import jakarta.validation.constraints.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.util.*;

/**
 * Extracts metadata from DTO and Entity classes
 * Cached for reuse across mapper generation
 */
@Slf4j
@Component
public class DtoMetadataExtractor {

    // Cache entity metadata to avoid repeated reflection
    private final Map<Class<?>, EntityMetadata> entityMetadataCache = new HashMap<>();

    /**
     * Extract complete DTO metadata
     */
    public DtoMetadata extractMetadata(Class<?> dtoClass,
                                       Class<?> entityClass,
                                       OperationType operation,
                                       Direction direction,
                                       boolean inheritValidations,
                                       boolean inheritConstraints) {

        EntityMetadata entityMeta = getEntityMetadata(entityClass);

        Map<String, FieldMapping> fieldMappings = new HashMap<>();
        Map<String, ValidationProfile> validationProfiles = new HashMap<>();

        // Process DTO fields
        for (Field dtoField : getAllFields(dtoClass)) {
            CrudXField fieldAnnotation = dtoField.getAnnotation(CrudXField.class);

            if (fieldAnnotation != null && fieldAnnotation.ignore()) {
                continue;
            }

            String dtoFieldName = dtoField.getName();
            String entityFieldName = fieldAnnotation != null && !fieldAnnotation.value().isEmpty()
                    ? fieldAnnotation.value()
                    : dtoFieldName;

            // Build field mapping
            FieldMapping mapping = FieldMapping.builder()
                    .dtoFieldName(dtoFieldName)
                    .entityFieldName(entityFieldName)
                    .dtoFieldType(dtoField.getType())
                    .ignored(false)
                    .build();

            // Handle nested objects
            if (dtoField.isAnnotationPresent(CrudXNested.class)) {
                CrudXNested nested = dtoField.getAnnotation(CrudXNested.class);
                mapping.setNested(true);
                mapping.setNestedDtoClass(nested.dto());
                mapping.setFetchStrategy(nested.fetch());
            }

            // Handle collections
            if (dtoField.isAnnotationPresent(CrudXCollection.class)) {
                CrudXCollection collection = dtoField.getAnnotation(CrudXCollection.class);
                mapping.setCollection(true);
                mapping.setNestedDtoClass(collection.elementDto());
            }

            fieldMappings.put(dtoFieldName, mapping);

            // Inherit validations
            if (inheritValidations && entityMeta.getValidationProfiles().containsKey(entityFieldName)) {
                ValidationProfile inheritedProfile = entityMeta.getValidationProfiles().get(entityFieldName);

                // Allow DTO to override required
                if (fieldAnnotation != null && !fieldAnnotation.required()) {
                    inheritedProfile = ValidationProfile.builder()
                            .required(false)
                            .minSize(inheritedProfile.getMinSize())
                            .maxSize(inheritedProfile.getMaxSize())
                            .pattern(inheritedProfile.getPattern())
                            .email(inheritedProfile.getEmail())
                            .min(inheritedProfile.getMin())
                            .max(inheritedProfile.getMax())
                            .build();
                }

                validationProfiles.put(dtoFieldName, inheritedProfile);
            }
        }

        return DtoMetadata.builder()
                .dtoClass(dtoClass)
                .entityClass(entityClass)
                .operation(operation)
                .direction(direction)
                .immutableFields(inheritConstraints ? entityMeta.getImmutableFields() : new HashSet<>())
                .uniqueConstraints(inheritConstraints ? entityMeta.getUniqueConstraints() : new ArrayList<>())
                .validationProfiles(validationProfiles)
                .fieldMappings(fieldMappings)
                .build();
    }

    /**
     * Get or extract entity metadata (cached)
     */
    private EntityMetadata getEntityMetadata(Class<?> entityClass) {
        return entityMetadataCache.computeIfAbsent(entityClass, this::extractEntityMetadata);
    }

    /**
     * Extract metadata from entity class
     */
    private EntityMetadata extractEntityMetadata(Class<?> entityClass) {
        Set<String> immutableFields = new HashSet<>();
        List<UniqueConstraintMetadata> uniqueConstraints = new ArrayList<>();
        Map<String, ValidationProfile> validationProfiles = new HashMap<>();

        // Extract immutable fields
        for (Field field : getAllFields(entityClass)) {
            if (field.isAnnotationPresent(CrudXImmutable.class)) {
                immutableFields.add(field.getName());
            }

            // Extract validation annotations
            ValidationProfile profile = extractValidationProfile(field);
            if (profile != null) {
                validationProfiles.put(field.getName(), profile);
            }
        }

        // Extract unique constraints
        CrudXUniqueConstraint[] constraints = entityClass.getAnnotationsByType(CrudXUniqueConstraint.class);
        for (CrudXUniqueConstraint constraint : constraints) {
            uniqueConstraints.add(UniqueConstraintMetadata.builder()
                    .fields(constraint.fields())
                    .message(constraint.message())
                    .build());
        }

        return EntityMetadata.builder()
                .entityClass(entityClass)
                .immutableFields(immutableFields)
                .uniqueConstraints(uniqueConstraints)
                .validationProfiles(validationProfiles)
                .build();
    }

    /**
     * Extract validation profile from field
     */
    private ValidationProfile extractValidationProfile(Field field) {
        ValidationProfile.ValidationProfileBuilder builder = ValidationProfile.builder();
        boolean hasConstraints = false;

        if (field.isAnnotationPresent(NotNull.class) || field.isAnnotationPresent(NotBlank.class)) {
            builder.required(true);
            hasConstraints = true;
        }

        if (field.isAnnotationPresent(Size.class)) {
            Size size = field.getAnnotation(Size.class);
            builder.minSize(size.min());
            builder.maxSize(size.max());
            hasConstraints = true;
        }

        if (field.isAnnotationPresent(Email.class)) {
            builder.email("true");
            hasConstraints = true;
        }

        if (field.isAnnotationPresent(Pattern.class)) {
            Pattern pattern = field.getAnnotation(Pattern.class);
            builder.pattern(pattern.regexp());
            hasConstraints = true;
        }

        if (field.isAnnotationPresent(Min.class)) {
            Min min = field.getAnnotation(Min.class);
            builder.min(min.value());
            hasConstraints = true;
        }

        if (field.isAnnotationPresent(Max.class)) {
            Max max = field.getAnnotation(Max.class);
            builder.max(max.value());
            hasConstraints = true;
        }

        return hasConstraints ? builder.build() : null;
    }

    private List<Field> getAllFields(Class<?> clazz) {
        List<Field> fields = new ArrayList<>();
        while (clazz != null && clazz != Object.class) {
            fields.addAll(Arrays.asList(clazz.getDeclaredFields()));
            clazz = clazz.getSuperclass();
        }
        return fields;
    }
}