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

package io.github.sachinnimbal.crudx.core.response;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.PropertySource;
import org.springframework.stereotype.Component;

/**
 * @author Sachin Nimbal
 * @see <a href="https://www.linkedin.com/in/sachin-nimbal/">LinkedIn Profile</a>
 */
@Component
@ConfigurationProperties(prefix = "") // CRITICAL: Use empty prefix to match top-level keys
@PropertySource("classpath:crudx.properties")
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
        private String version;
    }
}
