package io.github.sachinnimbal.crudx.core.config;

import io.github.sachinnimbal.crudx.core.dto.mapper.CrudXDynamicMapperFactory;
import io.github.sachinnimbal.crudx.core.dto.mapper.CrudXMapperGenerator;
import io.github.sachinnimbal.crudx.core.dto.mapper.CrudXMapperRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Auto-configuration for CrudX DTO feature.
 *
 * @author Sachin Nimbal
 * @since 1.0.2
 */
@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "crudx.dto", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(CrudXDTOProperties.class)
@ComponentScan(basePackages = "io.github.sachinnimbal.crudx.core.dto")
@Import({
        CrudXMapperRegistry.class,
        CrudXMapperGenerator.class,
        CrudXDynamicMapperFactory.class
})
public class CrudXDTOConfiguration {

    private static final String RESET = "\u001B[0m";
    private static final String GREEN = "\u001B[32m";
    private static final String CYAN = "\u001B[36m";
    private static final String BOLD = "\u001B[1m";

    public CrudXDTOConfiguration() {
        logInfo(CYAN + "========================================" + RESET);
        logInfo(BOLD + "  CrudX DTO Feature Initialized" + RESET);
        logInfo(GREEN + "  Zero-Boilerplate DTO Mapping" + RESET);
        logInfo(CYAN + "========================================" + RESET);
    }

    private void logInfo(String message) {
        System.out.println(message);
    }
}