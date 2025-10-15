package io.github.sachinnimbal.crudx.dto.validation;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ValidationResult {
    private boolean valid = true;
    private Map<String, List<String>> errors = new HashMap<>();

    public void addError(String field, String message) {
        this.valid = false;
        this.errors.computeIfAbsent(field, k -> new ArrayList<>()).add(message);
    }

    public static ValidationResult success() {
        return ValidationResult.builder().valid(true).build();
    }

    public String getErrorMessage() {
        if (valid) return null;

        StringBuilder sb = new StringBuilder("Validation failed: ");
        errors.forEach((field, messages) -> {
            sb.append(field).append(" - ").append(String.join(", ", messages)).append("; ");
        });
        return sb.toString();
    }
}