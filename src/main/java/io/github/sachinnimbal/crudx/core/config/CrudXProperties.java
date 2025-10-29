package io.github.sachinnimbal.crudx.core.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "crudx")
public class CrudXProperties {

    // ==================== CORE PROPERTIES ====================

    /**
     * Batch size for bulk operations
     * Default: 500
     */
    private int batchSize = 500;

    /**
     * Maximum allowed batch size to prevent memory issues
     * Default: 100,000
     */
    private Integer maxBatchSize = 100000;

    /**
     * Query timeout in milliseconds
     * Default: 30 seconds
     */
    private int queryTimeout = 30000;

    // ==================== DATABASE PROPERTIES ====================

    private Database database = new Database();

    @Data
    public static class Database {
        /**
         * Enable automatic database creation if it doesn't exist
         * Default: true
         */
        private boolean autoCreate = true;

        /**
         * Database connection pool size
         * Default: 20
         */
        private int connectionPoolSize = 20;

        /**
         * Connection timeout in milliseconds
         * Default: 30 seconds
         */
        private int connectionTimeout = 30000;
    }

    // ==================== DTO PROPERTIES ====================

    private Dto dto = new Dto();

    @Data
    public static class Dto {
        /**
         * Enable/disable DTO mapping feature
         * Default: true
         */
        private boolean enabled = true;

        /**
         * Comma-separated list of packages to scan for DTOs
         * Example: "com.example.dto,com.example.controller"
         */
        private String scanPackages = "";

        /**
         * Maximum nesting depth for nested DTOs
         * Default: 3
         */
        private int maxNestingDepth = 3;

        /**
         * Enable strict mode for field validation
         * Default: false
         */
        private boolean strictMode = false;

        /**
         * Enable caching of DTO mappings
         * Default: true
         */
        private boolean cacheEnabled = true;

        /**
         * Log DTO mapping operations
         * Default: true
         */
        private boolean logMappings = true;
    }

    // ==================== PERFORMANCE MONITORING PROPERTIES ====================

    private Performance performance = new Performance();

    @Data
    public static class Performance {
        /**
         * Enable performance monitoring
         * Default: false
         */
        private boolean enabled = false;

        /**
         * Enable performance dashboard
         * Default: true
         */
        private boolean dashboardEnabled = true;

        /**
         * Dashboard endpoint path
         * Default: /crudx/performance
         */
        private String dashboardPath = "/crudx/performance";

        /**
         * Maximum number of metrics to store in memory
         * Default: 1000
         */
        private int maxStoredMetrics = 1000;

        /**
         * Enable memory usage tracking (may impact performance)
         * Default: false
         */
        private boolean trackMemory = false;

        /**
         * Metrics retention period in minutes
         * Default: 60 minutes
         */
        private int retentionMinutes = 60;
    }

    // ==================== SWAGGER/API DOCUMENTATION PROPERTIES ====================

    private Swagger swagger = new Swagger();

    @Data
    public static class Swagger {
        /**
         * Enable Swagger/OpenAPI documentation
         * Default: true
         */
        private boolean enabled = true;
    }

    // ==================== JPA REPOSITORY PROPERTIES ====================

    private Jpa jpa = new Jpa();

    @Data
    public static class Jpa {
        /**
         * Additional packages to scan for JPA repositories
         * Default: empty (uses base packages only)
         */
        private String repositoryPackages = "";

        /**
         * Additional packages to scan for JPA entities
         * Default: empty (uses base packages only)
         */
        private String entityPackages = "";
    }

    // ==================== MONGODB REPOSITORY PROPERTIES ====================

    private Mongo mongo = new Mongo();

    @Data
    public static class Mongo {
        /**
         * Additional packages to scan for MongoDB repositories
         * Default: empty (uses base packages only)
         */
        private String repositoryPackages = "";
    }
}