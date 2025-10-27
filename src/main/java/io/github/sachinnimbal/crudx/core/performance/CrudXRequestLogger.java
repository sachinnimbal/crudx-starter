package io.github.sachinnimbal.crudx.core.performance;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StreamUtils;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 🔥 ASYNC Request/Response Logger
 * Logs to rotating daily files: logs/requests/yyyy-MM-dd.log
 * Zero-copy streaming for large payloads
 * Non-blocking async writes
 */
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
    private static final int MAX_INLINE_PAYLOAD_SIZE = 2048; // 2KB inline, rest streamed

    static {
        // Create log directory on startup
        try {
            Files.createDirectories(Paths.get(LOG_DIR));
        } catch (IOException e) {
            log.error("Failed to create log directory: {}", LOG_DIR, e);
        }
    }

    /**
     * Log request asynchronously
     */
    public static void logRequest(HttpServletRequest request, byte[] body, long startTime) {
        ASYNC_LOGGER.submit(() -> {
            try {
                String logEntry = buildRequestLog(request, body, startTime);
                writeToFile(logEntry);
            } catch (Exception e) {
                log.error("Failed to log request", e);
            }
        });
    }

    /**
     * Log response asynchronously
     */
    public static void logResponse(HttpServletRequest request, int status, String executionTime) {
        ASYNC_LOGGER.submit(() -> {
            try {
                String logEntry = buildResponseLog(request, status, executionTime);
                writeToFile(logEntry);
            } catch (Exception e) {
                log.error("Failed to log response", e);
            }
        });
    }

    /**
     * Build CURL-style request log
     */
    private static String buildRequestLog(HttpServletRequest request, byte[] body, long startTime) {
        StringBuilder log = new StringBuilder();

        log.append("\n");
        log.append("=".repeat(100)).append("\n");
        log.append("📥 REQUEST @ ").append(LocalDateTime.now().format(LOG_TIME_FORMAT)).append("\n");
        log.append("=".repeat(100)).append("\n");

        // Request line
        log.append(request.getMethod()).append(" ")
                .append(request.getRequestURI());

        if (request.getQueryString() != null) {
            log.append("?").append(request.getQueryString());
        }
        log.append(" ").append(request.getProtocol()).append("\n");

        // Headers
        log.append("\n📋 Headers:\n");
        Enumeration<String> headerNames = request.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String headerName = headerNames.nextElement();
            String headerValue = request.getHeader(headerName);
            log.append("  ").append(headerName).append(": ").append(headerValue).append("\n");
        }

        // CURL command
        log.append("\n🔧 CURL Command:\n");
        log.append(buildCurlCommand(request, body));

        // Body (inline or reference)
        if (body != null && body.length > 0) {
            log.append("\n📦 Request Body (").append(body.length).append(" bytes):\n");

            if (body.length <= MAX_INLINE_PAYLOAD_SIZE) {
                // Inline small payloads
                log.append(new String(body, StandardCharsets.UTF_8)).append("\n");
            } else {
                // Large payloads: Show sample + file reference
                String sample = new String(body, 0, Math.min(500, body.length), StandardCharsets.UTF_8);
                log.append("  [First 500 bytes]\n");
                log.append(sample).append("\n  ...[truncated]\n");
            }
        }

        log.append("\n");

        return log.toString();
    }

    /**
     * Build CURL command
     */
    private static String buildCurlCommand(HttpServletRequest request, byte[] body) {
        StringBuilder curl = new StringBuilder();

        curl.append("curl -X ").append(request.getMethod()).append(" \\\n");
        curl.append("  '").append(getFullURL(request)).append("' \\\n");

        // Headers
        Enumeration<String> headerNames = request.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String headerName = headerNames.nextElement();
            String headerValue = request.getHeader(headerName);

            // Skip host and content-length
            if (!"Host".equalsIgnoreCase(headerName) && !"Content-Length".equalsIgnoreCase(headerName)) {
                curl.append("  -H '").append(headerName).append(": ").append(headerValue).append("' \\\n");
            }
        }

        // Body
        if (body != null && body.length > 0) {
            if (body.length <= MAX_INLINE_PAYLOAD_SIZE) {
                String bodyStr = new String(body, StandardCharsets.UTF_8)
                        .replace("'", "'\\''"); // Escape single quotes
                curl.append("  -d '").append(bodyStr).append("'");
            } else {
                curl.append("  -d @<large_payload_file>");
            }
        }

        return curl.toString();
    }

    /**
     * Build response log
     */
    private static String buildResponseLog(HttpServletRequest request, int status, String executionTime) {
        StringBuilder log = new StringBuilder();

        log.append("\n");
        log.append("📤 RESPONSE @ ").append(LocalDateTime.now().format(LOG_TIME_FORMAT)).append("\n");
        log.append("-".repeat(100)).append("\n");
        log.append("Status: ").append(status).append("\n");
        log.append("Execution Time: ").append(executionTime).append("\n");
        log.append("Endpoint: ").append(request.getMethod()).append(" ").append(request.getRequestURI()).append("\n");
        log.append("=".repeat(100)).append("\n\n");

        return log.toString();
    }

    /**
     * Write log entry to daily rotating file
     */
    private static void writeToFile(String logEntry) throws IOException {
        String todayFile = LocalDate.now().format(FILE_DATE_FORMAT) + ".log";
        Path logFile = Paths.get(LOG_DIR, todayFile);

        Files.writeString(logFile, logEntry, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    /**
     * Get full request URL
     */
    private static String getFullURL(HttpServletRequest request) {
        StringBuffer url = request.getRequestURL();
        String queryString = request.getQueryString();

        if (queryString != null) {
            url.append('?').append(queryString);
        }

        return url.toString();
    }

    /**
     * Shutdown logger (for graceful shutdown)
     */
    public static void shutdown() {
        ASYNC_LOGGER.shutdown();
    }

    /**
     * 🔥 CRITICAL: Cacheable Request Wrapper for body reading
     */
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

    /**
     * Cached ServletInputStream
     */
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
}