package io.github.sachinnimbal.crudx.core.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "crudx.dto", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnClass(
        name = {
                "io.github.sachinnimbal.crudx.core.dto.mapper.CrudXMapperRegistry",
                "io.github.sachinnimbal.crudx.core.dto.mapper.CrudXMapperGenerator",
                "com.google.auto.service.AutoService"
        }
)
public class CrudXDTOAutoConfiguration {

    public CrudXDTOAutoConfiguration() {
        log.info("✓ CrudX DTO Auto-Configuration activated");
        log.info("  - DTO mapping enabled");
        log.info("  - Annotation processors available");
        log.info("  - Mapper beans will be created automatically");
    }

    /**
     * Swagger integration for DTO support
     * Only loaded if both DTOs are enabled AND Swagger is present
     */
    @Configuration
    @ConditionalOnProperty(prefix = "crudx.swagger", name = "enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnClass(name = "org.springdoc.core.configuration.SpringDocConfiguration")
    static class SwaggerDTOIntegration {
        public SwaggerDTOIntegration() {
            log.info("✓ Swagger DTO integration enabled");
        }
    }
}