package io.github.sachinnimbal.crudx.core.response;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.PropertySource;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "") // CRITICAL: Use empty prefix to match top-level keys
@PropertySource("classpath:crudx.properties")
@Data
public class CrudxMetadataProperties {

    private Author author = new Author();
    private Project project = new Project();
    private String path = "/crudx/swagger";
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
        private String version;
    }
}
