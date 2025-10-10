/*
 * Copyright 2025 Sachin Nimbal
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.sachinnimbal.crudx.core.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.List;


/**
 * @author Sachin Nimbal
 * @see <a href="https://www.linkedin.com/in/sachin-nimbal/">LinkedIn Profile</a>
 */
@Slf4j
@Configuration
@ConditionalOnClass(name = "org.springdoc.core.configuration.SpringDocConfiguration")
@ConditionalOnProperty(prefix = "crudx.swagger", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CrudXSwaggerConfiguration {

    private final Environment environment;

    public CrudXSwaggerConfiguration(Environment environment) {
        this.environment = environment;
        log.info("✓ CrudX Swagger/OpenAPI enabled");
    }

    @Bean
    public OpenAPI crudxOpenAPI() {
        String version = environment.getProperty("project.version", "1.0.0");
        String serverUrl = getServerUrl();

        return new OpenAPI()
                .info(new Info()
                        .title("CrudX Framework API")
                        .description("Auto-generated REST APIs by CrudX Framework - Zero Boilerplate CRUD Operations")
                        .version(version)
                        .contact(new Contact()
                                .name("CrudX Framework")
                                .email("sachinnimbal9@gmail.com")
                                .url("https://github.com/sachinnimbal/crudx-starter"))
                        .license(new License()
                                .name("Apache 2.0")
                                .url("https://www.apache.org/licenses/LICENSE-2.0")))
                .servers(List.of(
                        new Server()
                                .url(serverUrl)
                                .description("Current Server")
                ));
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
