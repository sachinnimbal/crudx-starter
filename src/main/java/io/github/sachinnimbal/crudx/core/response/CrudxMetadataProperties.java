package io.github.sachinnimbal.crudx.core.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.PropertySource;
import org.springframework.stereotype.Component;

/**
 * Single Configuration class to load all metadata from crudx-author.properties.
 * Uses an empty prefix ("") to match top-level keys (author.* and project.*)
 * via nested inner classes.
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