package io.github.sachinnimbal.crudx.core.config;

import io.github.sachinnimbal.crudx.core.enums.ImmutableStrategy;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "crudx.dto")
public class CrudXDtoProperties {

    private boolean enabled = true;
    private String[] scanPackages = {};

    // Inheritance behavior
    private boolean inheritValidations = true;
    private boolean inheritConstraints = true;
    private boolean inheritJpaMetadata = true;
    private boolean allowDtoOverrides = true;

    // Immutable field handling
    private ImmutableStrategy immutableStrategy = ImmutableStrategy.REJECT;
    private ImmutableStrategy immutableInUpdate = ImmutableStrategy.REJECT;

    // Unique constraint handling
    private boolean uniquePreCheck = true;
    private long uniquePreCheckTimeoutMs = 100;

    // Performance
    private boolean lazyInitialization = true;
    private int parallelBatchThreshold = 100;
    private int mapperPoolSize = 10;

    // Memory optimization
    private boolean useWeakReferences = true;
    private int fieldCacheSize = 10000;
    private boolean internStrings = true;

    // Features
    private boolean supportProjections = false;
    private boolean supportExpansions = false;
    private boolean stripNullFields = true;

    // Debug
    private boolean debug = false;
    private boolean logMappingTime = false;
}