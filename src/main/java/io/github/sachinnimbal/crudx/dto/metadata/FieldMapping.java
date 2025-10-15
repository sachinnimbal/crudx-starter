package io.github.sachinnimbal.crudx.dto.metadata;

import io.github.sachinnimbal.crudx.core.enums.FetchStrategy;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class FieldMapping {
    private String dtoFieldName;
    private String entityFieldName;
    private Class<?> dtoFieldType;
    private Class<?> entityFieldType;
    private boolean ignored;
    private boolean required;
    private boolean nested;
    private boolean collection;
    private Class<?> nestedDtoClass;
    private FetchStrategy fetchStrategy;
}