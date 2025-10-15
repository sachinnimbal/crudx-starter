package io.github.sachinnimbal.crudx.dto.metadata;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class UniqueConstraintMetadata {
    private String[] fields;
    private String message;
}