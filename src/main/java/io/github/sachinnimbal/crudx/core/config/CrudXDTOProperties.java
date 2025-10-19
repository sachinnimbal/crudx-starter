package io.github.sachinnimbal.crudx.core.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for CrudX DTO feature.
 *
 * @author Sachin Nimbal
 * @since 1.0.2
 */
@Data
@ConfigurationProperties(prefix = "crudx.dto")
public class CrudXDTOProperties {

    /**
     * Enable DTO mapping feature.
     */
    private boolean enabled = true;

    /**
     * Base packages to scan for DTO classes.
     * Comma-separated list. Example: "com.example.dto,com.example.api"
     * <p>
     * If not specified, framework will auto-detect from:
     * 1. Main application class package
     * 2. Common root packages (com, org, net, io)
     */
    private String scanPackages = "";

    /**
     * Maximum nesting depth for nested objects.
     */
    private int maxNestingDepth = 3;

    /**
     * Enable strict mode (fail on unknown DTO fields).
     */
    private boolean strictMode = false;

    /**
     * Cache DTO mappings for better performance.
     */
    private boolean cacheEnabled = true;

    /**
     * Log DTO mapping details at startup.
     */
    private boolean logMappings = true;
}