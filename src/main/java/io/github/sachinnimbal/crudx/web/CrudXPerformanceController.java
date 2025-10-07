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

package io.github.sachinnimbal.crudx.web;

import io.github.sachinnimbal.crudx.core.config.CrudXPerformanceProperties;
import io.github.sachinnimbal.crudx.core.response.CrudxMetadataProperties;
import io.github.sachinnimbal.crudx.core.metrics.CrudXPerformanceTracker;
import io.github.sachinnimbal.crudx.core.metrics.PerformanceMetric;
import io.github.sachinnimbal.crudx.core.metrics.PerformanceSummary;
import io.github.sachinnimbal.crudx.core.response.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author Sachin Nimbal
 * @see <a href="https://www.linkedin.com/in/sachin-nimbal/">LinkedIn Profile</a>
 */
@Slf4j
@RestController
@RequestMapping("${crudx.performance.dashboard-path:/crudx/performance}")
@ConditionalOnProperty(prefix = "crudx.performance", name = "enabled", havingValue = "true")
public class CrudXPerformanceController {

    private final CrudXPerformanceTracker tracker;
    private final CrudXPerformanceProperties properties;
    @Autowired
    private CrudxMetadataProperties metadataProperties;
    public CrudXPerformanceController(CrudXPerformanceTracker tracker, CrudXPerformanceProperties properties) {
        this.tracker = tracker;
        this.properties = properties;
    }

    @GetMapping("/summary")
    public ResponseEntity<ApiResponse<PerformanceSummary>> getSummary() {
        PerformanceSummary summary = tracker.getSummary();
        return ResponseEntity.ok(ApiResponse.success(summary, "Performance summary retrieved"));
    }

    @GetMapping("/metrics")
    public ResponseEntity<ApiResponse<List<PerformanceMetric>>> getMetrics() {
        List<PerformanceMetric> metrics = tracker.getMetrics();
        return ResponseEntity.ok(ApiResponse.success(metrics,
                String.format("Retrieved %d metrics", metrics.size())));
    }

    @GetMapping("/metrics/endpoint")
    public ResponseEntity<ApiResponse<List<PerformanceMetric>>> getMetricsByEndpoint(
            @RequestParam String endpoint) {
        List<PerformanceMetric> metrics = tracker.getMetricsByEndpoint(endpoint);
        return ResponseEntity.ok(ApiResponse.success(metrics,
                String.format("Retrieved %d metrics for endpoint: %s", metrics.size(), endpoint)));
    }

    @DeleteMapping("/metrics")
    public ResponseEntity<ApiResponse<Void>> clearMetrics() {
        tracker.clearMetrics();
        return ResponseEntity.ok(ApiResponse.success(null, "All metrics cleared"));
    }

    @GetMapping("/config")
    public ResponseEntity<ApiResponse<CrudXPerformanceProperties>> getConfig() {
        return ResponseEntity.ok(ApiResponse.success(properties, "Performance configuration"));
    }

    @GetMapping("/health")
    public ResponseEntity<ApiResponse<String>> health() {
        return ResponseEntity.ok(ApiResponse.success("OK", "Performance monitoring is active"));
    }

    @GetMapping(value = "/dashboard", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> dashboard() throws IOException {
        Resource resource = new ClassPathResource("index.html");
        String html = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        // Replace placeholder with actual API path
        html = html.replace("${API_BASE}", properties.getDashboardPath());

        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(html);
    }

    @GetMapping("/metadata")
    public ResponseEntity<?> getMetadata() {
        CrudxMetadataProperties.Author author = metadataProperties.getAuthor();
        CrudxMetadataProperties.Project project = metadataProperties.getProject();
        Map<String, Object> metadata = new HashMap<>();
        // Author Data
        metadata.put("authorName", author.getName());
        metadata.put("authorEmail", author.getEmail());
        metadata.put("authorLinkedin", author.getLinkedin());
        metadata.put("since", author.getSince());
        metadata.put("version", author.getVersion());

        // Project Data
        metadata.put("group", project.getGroup());
        metadata.put("artifact", project.getArtifact());
        metadata.put("projectVersion", project.getVersion());
        return ResponseEntity.ok(ApiResponse.success(metadata, "Metadata retrieved"));
    }
}
