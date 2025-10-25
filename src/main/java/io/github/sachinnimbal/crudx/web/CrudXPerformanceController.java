package io.github.sachinnimbal.crudx.web;

import io.github.sachinnimbal.crudx.core.config.CrudXPerformanceProperties;
import io.github.sachinnimbal.crudx.core.dto.metadata.CrudXDTOMetadataReader;
import io.github.sachinnimbal.crudx.core.metrics.CrudXPerformanceTracker;
import io.github.sachinnimbal.crudx.core.metrics.PerformanceMetric;
import io.github.sachinnimbal.crudx.core.metrics.PerformanceSummary;
import io.github.sachinnimbal.crudx.core.response.ApiResponse;
import io.github.sachinnimbal.crudx.core.response.CrudxMetadataProperties;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("${crudx.performance.dashboard-path:/crudx/performance}")
@ConditionalOnProperty(prefix = "crudx.performance", name = "enabled", havingValue = "true")
public class CrudXPerformanceController {

    private final CrudXPerformanceTracker tracker;
    private final CrudXPerformanceProperties properties;
    @Autowired
    private CrudxMetadataProperties metadataProperties;
    @Autowired(required = false)
    private CrudXDTOMetadataReader dtoMetadataReader;

    public CrudXPerformanceController(CrudXPerformanceTracker tracker, CrudXPerformanceProperties properties) {
        this.tracker = tracker;
        this.properties = properties;
    }

    @GetMapping("/dashboard-data")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getDashboardData() {
        PerformanceSummary summary = tracker.getSummary();
        List<PerformanceMetric> metrics = tracker.getMetrics();

        Map<String, Object> dashboardData = new HashMap<>();
        dashboardData.put("summary", summary);
        dashboardData.put("metrics", metrics);

        if (dtoMetadataReader != null && dtoMetadataReader.isMetadataAvailable()) {
            Map<String, Object> dtoInfo = new HashMap<>();
            dtoInfo.put("available", true);
            dtoInfo.put("entities", dtoMetadataReader.getAllMetadata());

            int totalRequestDTOs = dtoMetadataReader.getAllMetadata().values().stream()
                    .mapToInt(CrudXDTOMetadataReader.EntityDTOInfo::getRequestDTOCount)
                    .sum();

            int totalResponseDTOs = dtoMetadataReader.getAllMetadata().values().stream()
                    .mapToInt(CrudXDTOMetadataReader.EntityDTOInfo::getResponseDTOCount)
                    .sum();

            dtoInfo.put("totalRequestDTOs", totalRequestDTOs);
            dtoInfo.put("totalResponseDTOs", totalResponseDTOs);

            dashboardData.put("dtoMetadata", dtoInfo);
        } else {
            dashboardData.put("dtoMetadata", Map.of("available", false));
        }

        return ResponseEntity.ok(ApiResponse.success(dashboardData,
                "Dashboard data retrieved"));
    }

    @DeleteMapping("/metrics")
    public ResponseEntity<ApiResponse<Void>> clearMetrics() {
        tracker.clearMetrics();
        return ResponseEntity.ok(ApiResponse.success(null, "All metrics cleared"));
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
        Resource resource = new ClassPathResource("swagger-ui.html");
        String html = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        // Replace placeholders
        html = html.replace("${API_BASE}", properties.getDashboardPath());

        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(html);
    }

    @Hidden
    @GetMapping(value = "/endpoints", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> endpointsTable() throws IOException {
        Resource resource = new ClassPathResource("endpoints.html");
        String html = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        // Replace placeholder with actual API path if needed
        html = html.replace("${API_BASE}", properties.getDashboardPath());

        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(html);
    }

    @Hidden
    @GetMapping("/dto-metadata")
    public ResponseEntity<ApiResponse<?>> getDTOMetadata() {
        if (dtoMetadataReader == null || !dtoMetadataReader.isMetadataAvailable()) {
            return ResponseEntity.ok(ApiResponse.success(
                    Map.of(
                            "available", false,
                            "message", "No compile-time DTO metadata available"
                    ),
                    "DTO metadata not available"
            ));
        }

        Map<String, Object> response = new HashMap<>();
        response.put("available", true);
        response.put("entities", dtoMetadataReader.getAllMetadata());

        int totalRequestDTOs = dtoMetadataReader.getAllMetadata().values().stream()
                .mapToInt(CrudXDTOMetadataReader.EntityDTOInfo::getRequestDTOCount)
                .sum();

        int totalResponseDTOs = dtoMetadataReader.getAllMetadata().values().stream()
                .mapToInt(CrudXDTOMetadataReader.EntityDTOInfo::getResponseDTOCount)
                .sum();

        response.put("summary", Map.of(
                "totalEntities", dtoMetadataReader.getAllMetadata().size(),
                "totalRequestDTOs", totalRequestDTOs,
                "totalResponseDTOs", totalResponseDTOs
        ));

        return ResponseEntity.ok(ApiResponse.success(response,
                "DTO metadata retrieved successfully"));
    }

    @GetMapping("/test-dto-tracking")
    public ResponseEntity<ApiResponse<?>> testDtoTracking() {
        long start = System.currentTimeMillis();

        try {
            // Simulate DTO conversion work
            long dtoStart = System.nanoTime();

            // Simulate some DTO conversion work (e.g., 5-10ms)
            Thread.sleep(5);

            long dtoConversionMs = (System.nanoTime() - dtoStart) / 1_000_000;

            // Set request attributes to simulate DTO usage
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                HttpServletRequest request = attrs.getRequest();
                request.setAttribute("dtoConversionTime", dtoConversionMs);
                request.setAttribute("dtoUsed", true);

                log.info("✓ Test DTO tracking set: {} ms", dtoConversionMs);
            }

            Map<String, Object> result = new HashMap<>();
            result.put("message", "DTO tracking test");
            result.put("dtoConversionTimeMs", dtoConversionMs);
            result.put("dtoUsed", true);

            long executionTime = System.currentTimeMillis() - start;

            return ResponseEntity.ok(ApiResponse.success(result,
                    "Test completed - check performance metrics", executionTime));

        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - start;
            log.error("Test failed: {} | Time taken: {} ms",
                    e.getMessage(), executionTime, e);
            throw new RuntimeException("Test failed: " + e.getMessage(), e);
        }
    }
}
