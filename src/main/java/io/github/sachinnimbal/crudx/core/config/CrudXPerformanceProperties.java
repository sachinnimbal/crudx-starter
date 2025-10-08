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

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @author Sachin Nimbal
 * @see <a href="https://www.linkedin.com/in/sachin-nimbal/">LinkedIn Profile</a>
 */
@Data
@ConfigurationProperties(prefix = "crudx.performance")
public class CrudXPerformanceProperties {

    private boolean enabled = false;

    private boolean dashboardEnabled = true;

    private String dashboardPath = "/crudx/performance";

    private int maxStoredMetrics = 1000;

    private boolean trackMemory = false;

    private int retentionMinutes = 60;
}
