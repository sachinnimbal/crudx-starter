package io.github.sachinnimbal.crudx.web;

import io.github.sachinnimbal.crudx.core.dto.metadata.CrudXDTOMetadataReader;
import io.github.sachinnimbal.crudx.core.performance.CrudXMetricsRegistry;
import io.github.sachinnimbal.crudx.core.response.ApiResponse;
import io.github.sachinnimbal.crudx.core.response.CrudxMetadataProperties;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("${crudx.performance.dashboard-path:/crudx/performance}")
@ConditionalOnProperty(prefix = "crudx.performance", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CrudXPerformanceController {

    @Autowired
    private CrudxMetadataProperties metadataProperties;

    @Autowired(required = false)
    private CrudXDTOMetadataReader dtoMetadataReader;

    @Autowired
    private CrudXMetricsRegistry metricsRegistry;

    /**
     * 🔥 NEW: Full Performance Report Endpoint
     * Returns comprehensive performance metrics for all endpoints
     */
    @Hidden
    @GetMapping("/full")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getFullPerformanceReport() {
        long startTime = System.currentTimeMillis();

        try {
            Map<String, Object> fullReport = metricsRegistry.getFullPerformanceReport();

            long executionTime = System.currentTimeMillis() - startTime;

            return ResponseEntity.ok(ApiResponse.success(
                    fullReport,
                    "Full performance report retrieved successfully",
                    executionTime
            ));

        } catch (Exception e) {
            log.error("Failed to generate performance report", e);
            long executionTime = System.currentTimeMillis() - startTime;
            return ResponseEntity.internalServerError().body(
                    ApiResponse.error(
                            "Failed to generate performance report: " + e.getMessage(),
                            org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                            executionTime
                    )
            );
        }
    }

    /**
     * Get metrics for a specific endpoint
     */
    @Hidden
    @GetMapping("/endpoint")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getEndpointMetrics(
            @org.springframework.web.bind.annotation.RequestParam String endpoint) {

        long startTime = System.currentTimeMillis();

        var metricsOpt = metricsRegistry.getMetrics(endpoint);

        if (metricsOpt.isEmpty()) {
            long executionTime = System.currentTimeMillis() - startTime;
            return ResponseEntity.ok(ApiResponse.error(
                    "No metrics found for endpoint: " + endpoint,
                    org.springframework.http.HttpStatus.NOT_FOUND,
                    executionTime
            ));
        }

        Map<String, Object> response = new HashMap<>();
        response.put("endpoint", endpoint);
        response.put("metrics", metricsOpt.get());

        long executionTime = System.currentTimeMillis() - startTime;

        return ResponseEntity.ok(ApiResponse.success(
                response,
                "Endpoint metrics retrieved successfully",
                executionTime
        ));
    }

    /**
     * Clear all performance metrics
     */
    @Hidden
    @GetMapping("/clear")
    public ResponseEntity<ApiResponse<String>> clearAllMetrics() {
        long startTime = System.currentTimeMillis();

        try {
            metricsRegistry.clearAll();

            long executionTime = System.currentTimeMillis() - startTime;

            return ResponseEntity.ok(ApiResponse.success(
                    "All metrics cleared",
                    "Performance metrics reset successfully",
                    executionTime
            ));

        } catch (Exception e) {
            log.error("Failed to clear metrics", e);
            long executionTime = System.currentTimeMillis() - startTime;
            return ResponseEntity.internalServerError().body(
                    ApiResponse.error(
                            "Failed to clear metrics: " + e.getMessage(),
                            org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                            executionTime
                    )
            );
        }
    }

    @Hidden
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

    @Hidden
    @GetMapping(value = "/api-docs", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> swaggerUI() throws IOException {
        Resource resource = new ClassPathResource("index.html");
        String html = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        // Replace placeholders
        html = html.replace("${API_BASE}", metadataProperties.getPath());

        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(html);
    }
}