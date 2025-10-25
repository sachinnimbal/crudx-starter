package io.github.sachinnimbal.crudx.core.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "crudx.dto")
public class CrudXDTOProperties {

    private boolean enabled = true;

    private String scanPackages = "";

    private int maxNestingDepth = 3;

    private boolean strictMode = false;

    private boolean cacheEnabled = true;

    private boolean logMappings = true;
}
