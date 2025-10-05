package io.github.sachinnimbal.crudx.core.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;


/**
 * @author Sachin Nimbal
 * @version 1.0.0
 * @since 2025
 * @Contact: <a href="mailto:sachinnimbal9@gmail.com">sachinnimbal9@gmail.com</a>
 * @see <a href="https://www.linkedin.com/in/sachin-nimbal/">LinkedIn Profile</a>
 */
@Configuration
@ConditionalOnProperty(prefix = "crudx.performance", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(CrudXPerformanceProperties.class)
@ComponentScan(basePackages = {
        "io.github.sachinnimbal.crudx.core.metrics",
        "io.github.sachinnimbal.crudx.core.interceptor"
})
public class CrudXPerformanceConfiguration {

    public CrudXPerformanceConfiguration() {
        // Configuration will be loaded
    }
}