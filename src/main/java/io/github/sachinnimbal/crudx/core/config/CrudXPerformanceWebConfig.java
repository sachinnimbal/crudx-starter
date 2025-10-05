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
 * @version 1.0.0
 * @since 2025
 * @Contact: <a href="mailto:sachinnimbal9@gmail.com">sachinnimbal9@gmail.com</a>
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