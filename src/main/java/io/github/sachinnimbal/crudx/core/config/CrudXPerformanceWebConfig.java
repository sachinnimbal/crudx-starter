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

import io.github.sachinnimbal.crudx.core.interceptor.CrudXPerformanceInterceptor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * @author Sachin Nimbal
 * @see <a href="https://www.linkedin.com/in/sachin-nimbal/">LinkedIn Profile</a>
 */
@Slf4j
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "crudx.performance", name = "enabled", havingValue = "true")
public class CrudXPerformanceWebConfig implements WebMvcConfigurer {

    private final CrudXPerformanceInterceptor interceptor;
    private final CrudXPerformanceProperties properties;

    public CrudXPerformanceWebConfig(CrudXPerformanceInterceptor interceptor,
                                     CrudXPerformanceProperties properties) {
        this.interceptor = interceptor;
        this.properties = properties;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(properties.getDashboardPath() + "/**");

        log.info("CrudX Performance Interceptor registered");
    }
}
