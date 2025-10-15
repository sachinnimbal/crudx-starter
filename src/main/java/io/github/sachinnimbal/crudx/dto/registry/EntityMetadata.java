package io.github.sachinnimbal.crudx.dto.registry;


import io.github.sachinnimbal.crudx.dto.metadata.UniqueConstraintMetadata;
import io.github.sachinnimbal.crudx.dto.metadata.ValidationProfile;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Cached metadata extracted from Entity classes
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class EntityMetadata {
    private Class<?> entityClass;
    private Set<String> immutableFields;
    private List<UniqueConstraintMetadata> uniqueConstraints;
    private Map<String, ValidationProfile> validationProfiles;
}