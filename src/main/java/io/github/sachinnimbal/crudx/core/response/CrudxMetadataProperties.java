package io.github.sachinnimbal.crudx.core.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.PropertySource;
import org.springframework.stereotype.Component;


/**
 * @author Sachin Nimbal
 * @version 1.0.0
 * @since 2025
 * @Contact: <a href="mailto:sachinnimbal9@gmail.com">sachinnimbal9@gmail.com</a>
 * @see <a href="https://www.linkedin.com/in/sachin-nimbal/">LinkedIn Profile</a>
 */
@Component
@ConfigurationProperties(prefix = "") // CRITICAL: Use empty prefix to match top-level keys
@PropertySource("classpath:crudx-author.properties")
@Data
public class CrudxMetadataProperties {

    private Author author = new Author();
    private Project project = new Project();
    @Data
    public static class Author {
        private String name;
        private String email;
        private String version;
        private String since;
        private String linkedin;
    }

    @Data
    public static class Project {
        private String artifact;
        private String group;
    }
}