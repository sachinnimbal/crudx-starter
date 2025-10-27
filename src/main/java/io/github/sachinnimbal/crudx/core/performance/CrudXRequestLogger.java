package io.github.sachinnimbal.crudx.core.performance;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StreamUtils;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
public class CrudXRequestLogger {

    private static final ExecutorService ASYNC_LOGGER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "CrudX-Request-Logger");
        thread.setDaemon(true);
        return thread;
    });

    private static final String LOG_DIR = "logs/requests";
    private static final DateTimeFormatter FILE_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter LOG_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final int MAX_INLINE_PAYLOAD_SIZE = 10240; // 10KB inline (increased from 2KB)
    private static final int MAX_RESPONSE_BODY_SIZE = 51200; // 50KB max for response body

    static {
        // Create log directory on startup
        try {
            Files.createDirectories(Paths.get(LOG_DIR));
        } catch (IOException e) {
            log.error("Failed to create log directory: {}", LOG_DIR, e);
        }
    }

    /**
     * 🔥 Log request with body
     */
    public static void logRequest(HttpServletRequest request, byte[] body, long startTime) {
        // ✅ Deduplication check
        if (request.getAttribute("crudx.request.logged") != null) {
            return;
        }
        request.setAttribute("crudx.request.logged", true);

        // Capture request data
        RequestSnapshot snapshot = captureRequestSnapshot(request, body, startTime);

        // Async logging
        ASYNC_LOGGER.submit(() -> {
            try {
                String logEntry = buildRequestLog(snapshot);
                writeToFile(logEntry);
            } catch (Exception e) {
                log.error("Failed to log request", e);
            }
        });
    }

    /**
     * 🔥 ENHANCED: Log response with body
     */
    public static void logResponse(HttpServletRequest request,
                                   ContentCachingResponseWrapper responseWrapper,
                                   String executionTime) {
        // ✅ Deduplication check
        if (request.getAttribute("crudx.response.logged") != null) {
            return;
        }
        request.setAttribute("crudx.response.logged", true);

        // Capture response data (including body)
        ResponseSnapshot snapshot = captureResponseSnapshot(request, responseWrapper, executionTime);

        // Async logging
        ASYNC_LOGGER.submit(() -> {
            try {
                String logEntry = buildResponseLog(snapshot);
                writeToFile(logEntry);
            } catch (Exception e) {
                log.error("Failed to log response", e);
            }
        });
    }

    /**
     * Capture request snapshot
     */
    private static RequestSnapshot captureRequestSnapshot(HttpServletRequest request, byte[] body, long startTime) {
        RequestSnapshot snapshot = new RequestSnapshot();

        try {
            snapshot.timestamp = LocalDateTime.now().format(LOG_TIME_FORMAT);
            snapshot.method = request.getMethod();
            snapshot.requestURI = request.getRequestURI();
            snapshot.queryString = request.getQueryString();
            snapshot.protocol = request.getProtocol();
            snapshot.body = body;
            snapshot.startTime = startTime;

            // Capture headers
            snapshot.headers = new HashMap<>();
            Enumeration<String> headerNames = request.getHeaderNames();
            while (headerNames.hasMoreElements()) {
                String headerName = headerNames.nextElement();
                snapshot.headers.put(headerName, request.getHeader(headerName));
            }

            // Build full URL
            StringBuffer url = request.getRequestURL();
            if (request.getQueryString() != null) {
                url.append('?').append(request.getQueryString());
            }
            snapshot.fullURL = url.toString();

        } catch (Exception e) {
            log.error("Failed to capture request snapshot", e);
        }

        return snapshot;
    }

    /**
     * 🔥 ENHANCED: Capture response snapshot including body
     */
    private static ResponseSnapshot captureResponseSnapshot(HttpServletRequest request,
                                                            ContentCachingResponseWrapper responseWrapper,
                                                            String executionTime) {
        ResponseSnapshot snapshot = new ResponseSnapshot();

        try {
            snapshot.timestamp = LocalDateTime.now().format(LOG_TIME_FORMAT);
            snapshot.method = request.getMethod();
            snapshot.requestURI = request.getRequestURI();
            snapshot.status = responseWrapper.getStatus();
            snapshot.executionTime = executionTime;
            snapshot.contentType = responseWrapper.getContentType();

            // 🔥 CRITICAL: Capture response body
            byte[] responseBody = responseWrapper.getContentAsByteArray();
            if (responseBody != null && responseBody.length > 0) {
                // Limit size to prevent huge logs
                int bodySize = Math.min(responseBody.length, MAX_RESPONSE_BODY_SIZE);
                snapshot.responseBody = new byte[bodySize];
                System.arraycopy(responseBody, 0, snapshot.responseBody, 0, bodySize);
                snapshot.responseBodySize = responseBody.length;
                snapshot.bodyTruncated = responseBody.length > MAX_RESPONSE_BODY_SIZE;
            }

            // Capture response headers
            snapshot.responseHeaders = new HashMap<>();
            for (String headerName : responseWrapper.getHeaderNames()) {
                snapshot.responseHeaders.put(headerName, responseWrapper.getHeader(headerName));
            }

        } catch (Exception e) {
            log.error("Failed to capture response snapshot", e);
        }

        return snapshot;
    }

    /**
     * Build request log
     */
    private static String buildRequestLog(RequestSnapshot snapshot) {
        StringBuilder log = new StringBuilder();

        log.append("\n");
        log.append("=".repeat(100)).append("\n");
        log.append("📥 REQUEST @ ").append(snapshot.timestamp).append("\n");
        log.append("=".repeat(100)).append("\n");

        // Request line
        log.append(snapshot.method).append(" ")
                .append(snapshot.requestURI);

        if (snapshot.queryString != null) {
            log.append("?").append(snapshot.queryString);
        }
        log.append(" ").append(snapshot.protocol).append("\n");

        // Headers
        log.append("\n📋 Headers:\n");
        snapshot.headers.forEach((name, value) ->
                log.append("  ").append(name).append(": ").append(value).append("\n")
        );

        // CURL command
        log.append("\n🔧 CURL Command:\n");
        log.append(buildCurlCommand(snapshot));

        // Body
        if (snapshot.body != null && snapshot.body.length > 0) {
            log.append("\n📦 Request Body (").append(snapshot.body.length).append(" bytes):\n");

            if (snapshot.body.length <= MAX_INLINE_PAYLOAD_SIZE) {
                // Inline full body
                String bodyStr = new String(snapshot.body, StandardCharsets.UTF_8);
                log.append(formatJson(bodyStr)).append("\n");
            } else {
                // Show sample for large bodies
                String sample = new String(snapshot.body, 0, Math.min(1000, snapshot.body.length), StandardCharsets.UTF_8);
                log.append("  [First 1000 bytes]\n");
                log.append(formatJson(sample)).append("\n  ...[truncated]\n");
            }
        }

        log.append("\n");
        return log.toString();
    }

    /**
     * 🔥 ENHANCED: Build response log with body
     */
    private static String buildResponseLog(ResponseSnapshot snapshot) {
        StringBuilder log = new StringBuilder();

        log.append("\n");
        log.append("📤 RESPONSE @ ").append(snapshot.timestamp).append("\n");
        log.append("-".repeat(100)).append("\n");
        log.append("Status: ").append(snapshot.status).append("\n");
        log.append("Execution Time: ").append(snapshot.executionTime).append("\n");
        log.append("Endpoint: ").append(snapshot.method).append(" ").append(snapshot.requestURI).append("\n");

        if (snapshot.contentType != null) {
            log.append("Content-Type: ").append(snapshot.contentType).append("\n");
        }

        // 🔥 Response Headers
        if (snapshot.responseHeaders != null && !snapshot.responseHeaders.isEmpty()) {
            log.append("\n📋 Response Headers:\n");
            snapshot.responseHeaders.forEach((name, value) ->
                    log.append("  ").append(name).append(": ").append(value).append("\n")
            );
        }

        // 🔥 CRITICAL: Response Body
        if (snapshot.responseBody != null && snapshot.responseBody.length > 0) {
            log.append("\n📦 Response Body (").append(snapshot.responseBodySize).append(" bytes)");
            if (snapshot.bodyTruncated) {
                log.append(" [TRUNCATED TO ").append(MAX_RESPONSE_BODY_SIZE).append(" bytes]");
            }
            log.append(":\n");

            String bodyStr = new String(snapshot.responseBody, StandardCharsets.UTF_8);
            log.append(formatJson(bodyStr)).append("\n");
        }

        log.append("=".repeat(100)).append("\n\n");
        return log.toString();
    }

    /**
     * Build CURL command
     */
    private static String buildCurlCommand(RequestSnapshot snapshot) {
        StringBuilder curl = new StringBuilder();

        curl.append("curl -X ").append(snapshot.method).append(" \\\n");
        curl.append("  '").append(snapshot.fullURL).append("' \\\n");

        // Headers
        snapshot.headers.forEach((name, value) -> {
            if (!"Host".equalsIgnoreCase(name) && !"Content-Length".equalsIgnoreCase(name)) {
                curl.append("  -H '").append(name).append(": ").append(value).append("' \\\n");
            }
        });

        // Body
        if (snapshot.body != null && snapshot.body.length > 0) {
            if (snapshot.body.length <= MAX_INLINE_PAYLOAD_SIZE) {
                String bodyStr = new String(snapshot.body, StandardCharsets.UTF_8)
                        .replace("'", "'\\''")
                        .replace("\n", "")
                        .replace("\r", "");
                curl.append("  -d '").append(bodyStr).append("'");
            } else {
                curl.append("  -d @<request_body.json>");
            }
        }

        return curl.toString();
    }

    /**
     * 🔥 NEW: Format JSON for better readability
     */
    private static String formatJson(String json) {
        try {
            // Simple indentation for readability
            if (json.trim().startsWith("{") || json.trim().startsWith("[")) {
                // Already looks like JSON, add basic formatting
                return json.replace(",", ",\n  ")
                        .replace("{", "{\n  ")
                        .replace("}", "\n}")
                        .replace("[", "[\n  ")
                        .replace("]", "\n]");
            }
        } catch (Exception e) {
            // If formatting fails, return as-is
        }
        return json;
    }

    /**
     * Write to file
     */
    private static void writeToFile(String logEntry) throws IOException {
        String todayFile = LocalDate.now().format(FILE_DATE_FORMAT) + ".log";
        Path logFile = Paths.get(LOG_DIR, todayFile);

        Files.writeString(logFile, logEntry, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    /**
     * Shutdown logger
     */
    public static void shutdown() {
        ASYNC_LOGGER.shutdown();
    }

    // ==================== REQUEST WRAPPER ====================

    public static class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {
        private byte[] cachedBody;

        public CachedBodyHttpServletRequest(HttpServletRequest request) throws IOException {
            super(request);
            this.cachedBody = StreamUtils.copyToByteArray(request.getInputStream());
        }

        @Override
        public ServletInputStream getInputStream() {
            return new CachedBodyServletInputStream(this.cachedBody);
        }

        @Override
        public BufferedReader getReader() {
            ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(this.cachedBody);
            return new BufferedReader(new InputStreamReader(byteArrayInputStream, StandardCharsets.UTF_8));
        }

        public byte[] getCachedBody() {
            return cachedBody;
        }
    }

    private static class CachedBodyServletInputStream extends ServletInputStream {
        private final InputStream cachedBodyInputStream;

        public CachedBodyServletInputStream(byte[] cachedBody) {
            this.cachedBodyInputStream = new ByteArrayInputStream(cachedBody);
        }

        @Override
        public boolean isFinished() {
            try {
                return cachedBodyInputStream.available() == 0;
            } catch (IOException e) {
                return true;
            }
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int read() throws IOException {
            return cachedBodyInputStream.read();
        }
    }

    // ==================== SNAPSHOT CLASSES ====================

    private static class RequestSnapshot {
        String timestamp;
        String method;
        String requestURI;
        String queryString;
        String protocol;
        byte[] body;
        long startTime;
        Map<String, String> headers;
        String fullURL;
    }

    private static class ResponseSnapshot {
        String timestamp;
        String method;
        String requestURI;
        int status;
        String executionTime;
        String contentType;
        byte[] responseBody;
        int responseBodySize;
        boolean bodyTruncated;
        Map<String, String> responseHeaders;
    }
}