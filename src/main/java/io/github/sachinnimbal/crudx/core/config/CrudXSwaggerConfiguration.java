package io.github.sachinnimbal.crudx.core.config;

import io.github.sachinnimbal.crudx.core.dto.mapper.CrudXMapperRegistry;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import lombok.extern.slf4j.Slf4j;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.List;

@Slf4j
@Configuration
@ConditionalOnClass(name = "org.springdoc.core.configuration.SpringDocConfiguration")
@ConditionalOnProperty(prefix = "crudx.swagger", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CrudXSwaggerConfiguration {

    private final Environment environment;

    @Autowired(required = false)
    private CrudXMapperRegistry dtoRegistry;

    public CrudXSwaggerConfiguration(Environment environment) {
        this.environment = environment;
        log.info("✓ CrudX Swagger/OpenAPI enabled");
    }

    @Bean
    public OpenAPI crudxOpenAPI() {
        String version = environment.getProperty("project.version", "1.0.2");
        String serverUrl = getServerUrl();

        return new OpenAPI()
                .info(new Info()
                        .title("CrudX Framework API")
                        .description("CRUDX | The Next-Gen Multi-Database CRUD Framework for Spring Boot.")
                        .version(version)
                        .contact(new Contact()
                                .name("CrudX Framework")
                                .email("sachinnimbal9@gmail.com")
                                .url("https://github.com/sachinnimbal/crudx-starter"))
                        .license(new License()
                                .name("Apache 2.0")
                                .url("https://github.com/sachinnimbal/crudx-starter/blob/main/LICENSE")))
                .servers(List.of(
                        new Server()
                                .url(serverUrl)
                                .description("Current Server")
                ));
    }

    @Bean
    public OperationCustomizer crudxDtoSchemaCustomizer() {
        if (dtoRegistry != null) {
            log.info("✓ Swagger schema customization enabled (with DTO support)");
        } else {
            log.info("✓ Swagger schema customization enabled (entity-only mode)");
        }

        return new CrudXSwaggerDTOCustomizer(dtoRegistry);
    }

    private String getServerUrl() {
        String port = environment.getProperty("server.port", "8080");
        String contextPath = environment.getProperty("server.servlet.context-path", "");
        String host = environment.getProperty("server.address", "localhost");

        if (contextPath.endsWith("/")) {
            contextPath = contextPath.substring(0, contextPath.length() - 1);
        }

        return String.format("http://%s:%s%s", host, port, contextPath);
    }
}