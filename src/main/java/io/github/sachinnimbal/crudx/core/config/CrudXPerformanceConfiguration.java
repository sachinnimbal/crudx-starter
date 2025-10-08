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

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * @author Sachin Nimbal
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
