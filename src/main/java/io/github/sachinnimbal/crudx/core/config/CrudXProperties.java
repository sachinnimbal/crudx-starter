package io.github.sachinnimbal.crudx.core.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "crudx")
public class CrudXProperties {

    private int batchSize = 500;

    private int maxBatchSize = 100000;

    private int queryTimeout = 30000;

    private Database database = new Database();

    @Data
    public static class Database {
        private boolean autoCreate = true;
        private int connectionPoolSize = 20;
        private int connectionTimeout = 30000;
    }
}
