package io.github.sachinnimbal.crudx.core.performance;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;

import static io.github.sachinnimbal.crudx.core.util.TimeUtils.formatExecutionTime;

@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CrudXLoggingFilter implements Filter {

    @Autowired
    private CrudXMetricsRegistry metricsRegistry;

    private static final String REQUEST_START_TIME = "request.startTime";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        if (!(request instanceof HttpServletRequest) || !(response instanceof HttpServletResponse)) {
            chain.doFilter(request, response);
            return;
        }

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        // Skip logging for static resources and Swagger UI
        String uri = httpRequest.getRequestURI();
        if (shouldSkipLogging(uri)) {
            chain.doFilter(request, response);
            return;
        }

        long startTime = System.currentTimeMillis();
        httpRequest.setAttribute(REQUEST_START_TIME, startTime);

        // 🔥 CRITICAL: Wrap request to cache body for logging
        CrudXRequestLogger.CachedBodyHttpServletRequest cachedRequest =
                new CrudXRequestLogger.CachedBodyHttpServletRequest(httpRequest);

        // 🔥 Wrap response to capture status
        ContentCachingResponseWrapper cachedResponse =
                new ContentCachingResponseWrapper(httpResponse);

        try {
            // Log request async
            CrudXRequestLogger.logRequest(cachedRequest, cachedRequest.getCachedBody(), startTime);

            // Process request
            chain.doFilter(cachedRequest, cachedResponse);

        } finally {
            long endTime = System.currentTimeMillis();
            long executionTimeMs = endTime - startTime;

            // Log response async
            CrudXRequestLogger.logResponse(
                    cachedRequest,
                    cachedResponse.getStatus(),
                    formatExecutionTime(executionTimeMs)
            );

            // 🔥 Track basic metrics (detailed metrics tracked in controller)
            trackBasicMetrics(cachedRequest, executionTimeMs);

            // Copy response body
            cachedResponse.copyBodyToResponse();
        }
    }

    /**
     * Track basic metrics for this request
     */
    private void trackBasicMetrics(HttpServletRequest request, long executionTimeMs) {
        try {
            String endpoint = request.getMethod() + " " + request.getRequestURI();

            // Get existing metrics from request attributes (set by controller)
            EndpointMetrics metrics = (EndpointMetrics) request.getAttribute("endpoint.metrics");

            if (metrics != null) {
                // Controller already set detailed metrics
                metricsRegistry.recordMetrics(endpoint, metrics);
            } else {
                // Basic metrics for non-CrudX endpoints
                EndpointMetrics basicMetrics = EndpointMetrics.builder()
                        .endpoint(endpoint)
                        .method(request.getMethod())
                        .avgExecutionTimeMs(executionTimeMs)
                        .successCount(1)
                        .lastInvocationTime(System.currentTimeMillis())
                        .build();

                basicMetrics.calculateThroughput();
                metricsRegistry.recordMetrics(endpoint, basicMetrics);
            }
        } catch (Exception e) {
            log.debug("Failed to track basic metrics: {}", e.getMessage());
        }
    }

    /**
     * Skip logging for static resources and internal endpoints
     */
    private boolean shouldSkipLogging(String uri) {
        return uri.contains("/swagger-ui/") ||
                uri.contains("/v3/api-docs") ||
                uri.contains("/webjars/") ||
                uri.endsWith(".css") ||
                uri.endsWith(".js") ||
                uri.endsWith(".png") ||
                uri.endsWith(".jpg") ||
                uri.endsWith(".ico");
    }

    @Override
    public void init(FilterConfig filterConfig) {
        log.info("🔥 CrudX Logging & Metrics Filter initialized");
    }

    @Override
    public void destroy() {
        CrudXRequestLogger.shutdown();
        log.info("✓ CrudX Logging Filter destroyed");
    }
}