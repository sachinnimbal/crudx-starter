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
import org.springframework.core.env.Environment;

@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "crudx.dto", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(CrudXProperties.class)
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
    private static final String RED = "\u001B[31m";

    public CrudXDTOConfiguration(Environment environment) {
        boolean dtoEnabled = environment.getProperty("crudx.dto.enabled", Boolean.class, true);

        if (!dtoEnabled) {
            logError(RED + "========================================" + RESET);
            logError(RED + "  CONFIGURATION ERROR!" + RESET);
            logError(RED + "========================================" + RESET);
            logError(RED + "  CrudXDTOConfiguration loaded even though" + RESET);
            logError(RED + "  crudx.dto.enabled=false" + RESET);
            logError(RED + "  This should never happen!" + RESET);
            logError(RED + "========================================" + RESET);
            throw new IllegalStateException(
                    "DTO configuration loaded when crudx.dto.enabled=false. Check your @ConditionalOnProperty.");
        }

        logInfo(CYAN + "========================================" + RESET);
        logInfo(BOLD + "  CrudX DTO Feature Initialized" + RESET);
        logInfo(GREEN + "  Zero-Boilerplate DTO Mapping" + RESET);
        logInfo(CYAN + "========================================" + RESET);
    }

    private void logInfo(String message) {
        System.out.println(message);
    }

    private void logError(String message) {
        System.err.println(message);
    }
}
