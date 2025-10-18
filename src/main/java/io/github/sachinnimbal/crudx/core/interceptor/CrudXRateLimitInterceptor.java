package io.github.sachinnimbal.crudx.core.interceptor;

import com.google.common.util.concurrent.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


@Slf4j
@Component
@ConditionalOnProperty(prefix = "crudx.rate-limit", name = "enabled", havingValue = "true")
public class CrudXRateLimitInterceptor implements HandlerInterceptor {

    private final Map<String, RateLimiter> limiters = new ConcurrentHashMap<>();

    @Value("${crudx.rate-limit.requests-per-second:100}")
    private double requestsPerSecond;

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) {

        String clientId = getClientIdentifier(request);
        RateLimiter limiter = limiters.computeIfAbsent(clientId,
                k -> RateLimiter.create(requestsPerSecond));

        if (!limiter.tryAcquire()) {
            log.warn("Rate limit exceeded for client: {}", clientId);
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType("application/json");

            try {
                response.getWriter().write(
                        "{\"error\":\"Too many requests. Please slow down.\"}"
                );
            } catch (IOException e) {
                log.error("Error writing rate limit response", e);
            }

            return false;
        }

        return true;
    }

    private String getClientIdentifier(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        return forwarded != null ? forwarded.split(",")[0] : request.getRemoteAddr();
    }
}