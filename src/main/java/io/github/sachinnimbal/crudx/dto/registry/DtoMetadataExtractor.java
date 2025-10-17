package io.github.sachinnimbal.crudx.dto.registry;

import io.github.sachinnimbal.crudx.core.annotations.CrudXImmutable;
import io.github.sachinnimbal.crudx.core.annotations.CrudXUniqueConstraint;
import io.github.sachinnimbal.crudx.core.annotations.dto.CrudXCollection;
import io.github.sachinnimbal.crudx.core.annotations.dto.CrudXComputed;
import io.github.sachinnimbal.crudx.core.annotations.dto.CrudXField;
import io.github.sachinnimbal.crudx.core.annotations.dto.CrudXNested;
import io.github.sachinnimbal.crudx.core.enums.Direction;
import io.github.sachinnimbal.crudx.core.enums.OperationType;
import io.github.sachinnimbal.crudx.dto.metadata.DtoMetadata;
import io.github.sachinnimbal.crudx.dto.metadata.FieldMapping;
import io.github.sachinnimbal.crudx.dto.metadata.UniqueConstraintMetadata;
import io.github.sachinnimbal.crudx.dto.metadata.ValidationProfile;
import jakarta.validation.constraints.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.util.*;

@Slf4j
@Component
public class DtoMetadataExtractor {

    private final Map<Class<?>, EntityMetadata> entityMetadataCache = new HashMap<>();

    /**
     * Extract complete DTO metadata with all annotation support
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

        for (Field dtoField : getAllFields(dtoClass)) {
            CrudXField fieldAnnotation = dtoField.getAnnotation(CrudXField.class);

            if (fieldAnnotation != null && fieldAnnotation.ignore()) {
                continue;
            }

            // Handle @CrudXComputed fields
            if (dtoField.isAnnotationPresent(CrudXComputed.class)) {
                CrudXComputed computed = dtoField.getAnnotation(CrudXComputed.class);
                FieldMapping mapping = FieldMapping.builder()
                        .dtoFieldName(dtoField.getName())
                        .dtoFieldType(dtoField.getType())
                        .computed(true)
                        .spelExpression(computed.expression())
                        .required(fieldAnnotation != null && fieldAnnotation.required())
                        .build();
                fieldMappings.put(dtoField.getName(), mapping);

                log.debug("Registered computed field: {} with expression: {}",
                        dtoField.getName(), computed.expression());
                continue;
            }

            String dtoFieldName = dtoField.getName();
            String entityFieldName = fieldAnnotation != null && !fieldAnnotation.value().isEmpty()
                    ? fieldAnnotation.value()
                    : dtoFieldName;

            FieldMapping.FieldMappingBuilder mappingBuilder = FieldMapping.builder()
                    .dtoFieldName(dtoFieldName)
                    .entityFieldName(entityFieldName)
                    .dtoFieldType(dtoField.getType())
                    .ignored(false)
                    .required(fieldAnnotation != null && fieldAnnotation.required());

            // Handle @CrudXNested
            if (dtoField.isAnnotationPresent(CrudXNested.class)) {
                CrudXNested nested = dtoField.getAnnotation(CrudXNested.class);
                mappingBuilder.nested(true)
                        .nestedDtoClass(nested.dto())
                        .fetchStrategy(nested.fetch());

                log.debug("Registered nested field: {} with DTO: {} and fetch strategy: {}",
                        dtoFieldName, nested.dto().getSimpleName(), nested.fetch());
            }

            // Handle @CrudXCollection
            if (dtoField.isAnnotationPresent(CrudXCollection.class)) {
                CrudXCollection collection = dtoField.getAnnotation(CrudXCollection.class);
                mappingBuilder.collection(true)
                        .collectionElementType(collection.elementDto())
                        .maxCollectionSize(collection.maxSize());

                log.debug("Registered collection field: {} with element DTO: {} and maxSize: {}",
                        dtoFieldName, collection.elementDto().getSimpleName(), collection.maxSize());
            }

            FieldMapping mapping = mappingBuilder.build();
            fieldMappings.put(dtoFieldName, mapping);

            // Inherit validations from entity
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

    private EntityMetadata getEntityMetadata(Class<?> entityClass) {
        return entityMetadataCache.computeIfAbsent(entityClass, this::extractEntityMetadata);
    }

    private EntityMetadata extractEntityMetadata(Class<?> entityClass) {
        Set<String> immutableFields = new HashSet<>();
        List<UniqueConstraintMetadata> uniqueConstraints = new ArrayList<>();
        Map<String, ValidationProfile> validationProfiles = new HashMap<>();

        for (Field field : getAllFields(entityClass)) {
            if (field.isAnnotationPresent(CrudXImmutable.class)) {
                immutableFields.add(field.getName());
                log.debug("Registered immutable field: {} in entity: {}",
                        field.getName(), entityClass.getSimpleName());
            }

            ValidationProfile profile = extractValidationProfile(field);
            if (profile != null) {
                validationProfiles.put(field.getName(), profile);
            }
        }

        CrudXUniqueConstraint[] constraints = entityClass.getAnnotationsByType(CrudXUniqueConstraint.class);
        for (CrudXUniqueConstraint constraint : constraints) {
            uniqueConstraints.add(UniqueConstraintMetadata.builder()
                    .fields(constraint.fields())
                    .message(constraint.message())
                    .build());

            log.debug("Registered unique constraint on fields: {} in entity: {}",
                    Arrays.toString(constraint.fields()), entityClass.getSimpleName());
        }

        return EntityMetadata.builder()
                .entityClass(entityClass)
                .immutableFields(immutableFields)
                .uniqueConstraints(uniqueConstraints)
                .validationProfiles(validationProfiles)
                .build();
    }

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