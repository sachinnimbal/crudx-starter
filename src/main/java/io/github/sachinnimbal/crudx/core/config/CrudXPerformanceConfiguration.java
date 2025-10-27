package io.github.sachinnimbal.crudx.core.config;

import io.github.sachinnimbal.crudx.core.performance.CrudXLoggingFilter;
import io.github.sachinnimbal.crudx.core.performance.CrudXMetricsRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "crudx.performance", name = "enabled", havingValue = "true", matchIfMissing = true)
@ComponentScan(basePackages = "io.github.sachinnimbal.crudx.core.performance")
@Import({CrudXMetricsRegistry.class, CrudXLoggingFilter.class})
public class CrudXPerformanceConfiguration {

    private static final String RESET = "\u001B[0m";
    private static final String GREEN = "\u001B[32m";
    private static final String CYAN = "\u001B[36m";
    private static final String BOLD = "\u001B[1m";

    public CrudXPerformanceConfiguration() {
        logInfo(CYAN + "========================================" + RESET);
        logInfo(BOLD + "  CrudX Performance Tracking Enabled" + RESET);
        logInfo(GREEN + "  ✓ Metrics Registry Active" + RESET);
        logInfo(GREEN + "  ✓ Request/Response Logging Active" + RESET);
        logInfo(GREEN + "  ✓ Endpoint: /crudx/performance/full" + RESET);
        logInfo(CYAN + "========================================" + RESET);
    }

    @Bean
    @ConditionalOnProperty(prefix = "crudx.performance", name = "logging.enabled", havingValue = "true", matchIfMissing = true)
    public CrudXLoggingFilter crudxLoggingFilter() {
        log.info("✓ CrudX Request Logging Filter initialized");
        log.info("  Logs location: logs/requests/yyyy-MM-dd.log");
        return new CrudXLoggingFilter();
    }

    @Bean
    public CrudXMetricsRegistry crudxMetricsRegistry() {
        log.info("✓ CrudX Metrics Registry initialized");
        return new CrudXMetricsRegistry();
    }

    private void logInfo(String message) {
        System.out.println(message);
    }
}