package io.github.sachinnimbal.crudx.dto.validation;

import io.github.sachinnimbal.crudx.core.enums.Direction;
import io.github.sachinnimbal.crudx.core.enums.OperationType;
import io.github.sachinnimbal.crudx.dto.metadata.DtoMetadata;
import io.github.sachinnimbal.crudx.dto.metadata.ValidationProfile;
import io.github.sachinnimbal.crudx.dto.registry.DtoRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.util.*;

/**
 * Validates DTOs using inherited constraints from Entity
 */
@Slf4j
@Component
public class DtoValidator {

    private final DtoRegistry registry;

    public DtoValidator(DtoRegistry registry) {
        this.registry = registry;
    }

    /**
     * Validate DTO against inherited rules
     */
    public <D> ValidationResult validate(D dto, Class<?> entityClass, OperationType operation) {
        DtoMetadata metadata = registry.findDto(entityClass, operation, Direction.REQUEST);

        if (metadata == null) {
            return ValidationResult.success(); // No DTO configured
        }

        ValidationResult result = new ValidationResult();

        // Validate each field
        for (Map.Entry<String, ValidationProfile> entry : metadata.getValidationProfiles().entrySet()) {
            String fieldName = entry.getKey();
            ValidationProfile profile = entry.getValue();

            try {
                Field field = dto.getClass().getDeclaredField(fieldName);
                field.setAccessible(true);
                Object value = field.get(dto);

                // Required check
                if (profile.isRequired() && (value == null ||
                        (value instanceof String && ((String) value).isBlank()))) {
                    result.addError(fieldName, "Field is required");
                    continue;
                }

                if (value == null) continue; // Skip other validations for null

                // Size validation
                if (value instanceof String) {
                    String strValue = (String) value;
                    if (profile.getMinSize() != null && strValue.length() < profile.getMinSize()) {
                        result.addError(fieldName, String.format(
                                "Size must be at least %d characters", profile.getMinSize()));
                    }
                    if (profile.getMaxSize() != null && strValue.length() > profile.getMaxSize()) {
                        result.addError(fieldName, String.format(
                                "Size must not exceed %d characters", profile.getMaxSize()));
                    }
                }

                // Email validation
                if (profile.getEmail() != null && value instanceof String) {
                    if (!isValidEmail((String) value)) {
                        result.addError(fieldName, "Invalid email format");
                    }
                }

                // Pattern validation
                if (profile.getPattern() != null && value instanceof String) {
                    if (!((String) value).matches(profile.getPattern())) {
                        result.addError(fieldName, "Does not match required pattern");
                    }
                }

                // Min/Max validation
                if (value instanceof Number) {
                    Number numValue = (Number) value;
                    if (profile.getMin() != null && numValue.longValue() < ((Number) profile.getMin()).longValue()) {
                        result.addError(fieldName, String.format(
                                "Value must be at least %s", profile.getMin()));
                    }
                    if (profile.getMax() != null && numValue.longValue() > ((Number) profile.getMax()).longValue()) {
                        result.addError(fieldName, String.format(
                                "Value must not exceed %s", profile.getMax()));
                    }
                }

            } catch (NoSuchFieldException e) {
                log.warn("Field {} not found in DTO", fieldName);
            } catch (IllegalAccessException e) {
                log.error("Cannot access field {}", fieldName, e);
            }
        }

        return result;
    }

    /**
     * Validate immutable field updates
     */
    public ValidationResult validateImmutableFields(Map<String, Object> updates,
                                                    Class<?> entityClass,
                                                    OperationType operation) {
        DtoMetadata metadata = registry.findDto(entityClass, operation, Direction.REQUEST);

        if (metadata == null || metadata.getImmutableFields().isEmpty()) {
            return ValidationResult.success();
        }

        ValidationResult result = new ValidationResult();

        for (String immutableField : metadata.getImmutableFields()) {
            if (updates.containsKey(immutableField)) {
                result.addError(immutableField,
                        "This field is immutable and cannot be updated");
            }
        }

        return result;
    }

    private boolean isValidEmail(String email) {
        String emailRegex = "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$";
        return email != null && email.matches(emailRegex);
    }
}