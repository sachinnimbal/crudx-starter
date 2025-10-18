package io.github.sachinnimbal.crudx.core.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "crudx")
public class CrudXProperties {

    /**
     * Default batch size for bulk operations
     */
    private int batchSize = 500;

    /**
     * Maximum allowed batch size
     */
    private int maxBatchSize = 100000;

    /**
     * Query timeout in milliseconds
     */
    private int queryTimeout = 30000;

    /**
     * Enable automatic database creation
     */
    private Database database = new Database();

    @Data
    public static class Database {
        private boolean autoCreate = true;
        private int connectionPoolSize = 20;
        private int connectionTimeout = 30000;
    }
}