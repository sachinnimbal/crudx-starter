package io.github.sachinnimbal.crudx.core.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(prefix = "crudx.performance", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(CrudXProperties.class)
@ComponentScan(basePackages = {
        "io.github.sachinnimbal.crudx.core.metrics",
        "io.github.sachinnimbal.crudx.core.interceptor"
})
public class CrudXPerformanceConfiguration {

    public CrudXPerformanceConfiguration() {
        // Configuration will be loaded
    }
}