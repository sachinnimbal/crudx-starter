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

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.HashMap;
import java.util.Map;

/**
 * @author Sachin Nimbal
 * @see <a href="https://www.linkedin.com/in/sachin-nimbal/">LinkedIn Profile</a>
 */
@Slf4j
public class CrudXSmartExcludeInitializer implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String sqlUrl = environment.getProperty("spring.datasource.url");
        boolean hasSqlConfig = sqlUrl != null && !sqlUrl.trim().isEmpty();

        // Only exclude SQL auto-configurations if no SQL datasource is configured
        if (!hasSqlConfig) {
            Map<String, Object> excludeProps = new HashMap<>();
            excludeProps.put("spring.autoconfigure.exclude",
                    "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration," +
                            "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration," +
                            "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration");

            environment.getPropertySources().addFirst(
                    new MapPropertySource("crudxSmartExclude", excludeProps)
            );

            log.debug("CrudX: SQL datasource not configured - excluding JPA/DataSource auto-configuration");
        } else {
            log.debug("CrudX: SQL datasource configured - JPA auto-configuration will proceed");
        }
    }
}
