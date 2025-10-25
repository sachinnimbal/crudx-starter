package io.github.sachinnimbal.crudx.core.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "crudx.performance")
public class CrudXPerformanceProperties {

    private boolean enabled = false;

    private boolean dashboardEnabled = true;

    private String dashboardPath = "/crudx/performance";

    private int maxStoredMetrics = 1000;

    private boolean trackMemory = false;

    private int retentionMinutes = 60;
}
