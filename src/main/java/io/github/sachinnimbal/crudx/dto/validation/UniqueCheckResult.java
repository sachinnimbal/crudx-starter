package io.github.sachinnimbal.crudx.dto.validation;

import io.github.sachinnimbal.crudx.dto.metadata.UniqueConstraintMetadata;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class UniqueCheckResult {
    private boolean unique = true;
    private UniqueConstraintMetadata violatedConstraint;
    private String message;

    public static UniqueCheckResult success() {
        return UniqueCheckResult.builder().unique(true).build();
    }
}