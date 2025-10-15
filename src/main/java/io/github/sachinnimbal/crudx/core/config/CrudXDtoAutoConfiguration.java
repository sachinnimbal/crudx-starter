package io.github.sachinnimbal.crudx.core.config;

import io.github.sachinnimbal.crudx.dto.registry.DtoRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;


@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "crudx.dto", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(CrudXDtoProperties.class)
@Import(CrudXDtoScannerConfiguration.class)
public class CrudXDtoAutoConfiguration {

    private final CrudXDtoProperties properties;

    @Autowired
    public CrudXDtoAutoConfiguration(CrudXDtoProperties properties) {
        this.properties = properties;
        printBanner();
    }

    @Bean
    public DtoRegistry dtoRegistry() {
        log.info("✓ CrudX DTO Registry initialized (lazy-loading enabled)");
        return new DtoRegistry();
    }

    private void printBanner() {
        log.info("╔════════════════════════════════════════════════════════╗");
        log.info("║        CrudX DTO Framework v2.0 - ACTIVATED          ║");
        log.info("╠════════════════════════════════════════════════════════╣");
        log.info("║  ✓ Zero Code Duplication                              ║");
        log.info("║  ✓ Automatic Validation Inheritance                   ║");
        log.info("║  ✓ Lazy DTO Loading (On-Demand)                       ║");
        log.info("║  ✓ High-Performance Mapping                           ║");
        log.info("║  ✓ 100% Backward Compatible                           ║");
        log.info("╚════════════════════════════════════════════════════════╝");
        log.info("Configuration:");
        log.info("  → Inherit Validations: {}", properties.isInheritValidations());
        log.info("  → Inherit Constraints: {}", properties.isInheritConstraints());
        log.info("  → Lazy Initialization: {}", properties.isLazyInitialization());
        log.info("  → Immutable Strategy: {}", properties.getImmutableStrategy());
    }
}