package io.github.sachinnimbal.crudx.core.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;


/**
 * @author Sachin Nimbal
 * @version 1.0.0
 * @since 2025
 * @Contact: <a href="mailto:sachinnimbal9@gmail.com">sachinnimbal9@gmail.com</a>
 * @see <a href="https://www.linkedin.com/in/sachin-nimbal/">LinkedIn Profile</a>
 */
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