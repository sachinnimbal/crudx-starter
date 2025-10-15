package io.github.sachinnimbal.crudx.dto.metadata;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ValidationProfile {
    private boolean required;
    private Integer minSize;
    private Integer maxSize;
    private String pattern;
    private String email;
    private Object min;
    private Object max;
    private List<String> customValidators;
}
