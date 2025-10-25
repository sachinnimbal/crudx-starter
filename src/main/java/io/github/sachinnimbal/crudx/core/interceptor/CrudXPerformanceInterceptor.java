package io.github.sachinnimbal.crudx.core.interceptor;

import io.github.sachinnimbal.crudx.core.metrics.CrudXPerformanceTracker;
import io.github.sachinnimbal.crudx.web.CrudXController;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "crudx.performance", name = "enabled", havingValue = "true")
public class CrudXPerformanceInterceptor implements HandlerInterceptor {

    private static final String START_TIME_ATTR = "crudx.startTime";
    private static final String START_MEMORY_ATTR = "crudx.startMemory";
    private static final String ENTITY_NAME_ATTR = "crudx.entityName";

    private final CrudXPerformanceTracker tracker;
    private final ThreadMXBean threadMXBean;

    public CrudXPerformanceInterceptor(CrudXPerformanceTracker tracker) {
        this.tracker = tracker;
        this.threadMXBean = ManagementFactory.getThreadMXBean();

        // Enable thread memory allocation measurement if supported
        if (threadMXBean instanceof com.sun.management.ThreadMXBean sunThreadMXBean) {
            if (!sunThreadMXBean.isThreadAllocatedMemorySupported()) {
                log.warn("Thread memory allocation tracking not supported on this JVM");
            } else if (!sunThreadMXBean.isThreadAllocatedMemoryEnabled()) {
                sunThreadMXBean.setThreadAllocatedMemoryEnabled(true);
                log.info("Thread memory allocation tracking enabled");
            }
        }
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) {

        if (handler instanceof HandlerMethod handlerMethod) {
            Class<?> beanType = handlerMethod.getBeanType();

            if (CrudXController.class.isAssignableFrom(beanType)) {
                // Record start time
                request.setAttribute(START_TIME_ATTR, System.currentTimeMillis());

                // Record thread-specific memory allocation (accurate!)
                long threadId = Thread.currentThread().threadId();
                if (threadMXBean instanceof com.sun.management.ThreadMXBean sunThreadMXBean) {
                    if (sunThreadMXBean.isThreadAllocatedMemorySupported()) {
                        long allocatedBytes = sunThreadMXBean.getThreadAllocatedBytes(threadId);
                        request.setAttribute(START_MEMORY_ATTR, allocatedBytes);
                    }
                }

                // Extract entity name
                try {
                    String entityName = extractEntityName(beanType);
                    request.setAttribute(ENTITY_NAME_ATTR, entityName);
                } catch (Exception e) {
                    log.debug("Could not extract entity name", e);
                }
            }
        }

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {

        Long startTime = (Long) request.getAttribute(START_TIME_ATTR);
        Long startMemory = (Long) request.getAttribute(START_MEMORY_ATTR);

        if (startTime != null) {
            long executionTime = System.currentTimeMillis() - startTime;
            String endpoint = request.getRequestURI();
            String method = request.getMethod();
            String entityName = (String) request.getAttribute(ENTITY_NAME_ATTR);

            boolean success = response.getStatus() < 400 && ex == null;
            String errorType = null;

            if (!success) {
                if (ex != null) {
                    errorType = ex.getClass().getSimpleName();
                } else if (response.getStatus() >= 400) {
                    errorType = "HTTP_" + response.getStatus();
                }
            }

            // Calculate memory
            Long memoryDeltaKb = null;
            if (startMemory != null && threadMXBean instanceof com.sun.management.ThreadMXBean sunThreadMXBean) {
                try {
                    long threadId = Thread.currentThread().threadId();
                    long endMemory = sunThreadMXBean.getThreadAllocatedBytes(threadId);
                    long allocatedBytes = endMemory - startMemory;
                    memoryDeltaKb = allocatedBytes / 1024;

                    // Validate memory value
                    if (memoryDeltaKb < 0 || memoryDeltaKb > 3145728) { // > 3GB is unrealistic
                        log.debug("Invalid memory value detected: {} KB, setting to null", memoryDeltaKb);
                        memoryDeltaKb = null;
                    }
                } catch (Exception e) {
                    log.debug("Error measuring thread memory allocation", e);
                }
            }

            // 🔥 FIX: Get DTO conversion time from request attribute (set by controller)
            Long dtoConversionTime = (Long) request.getAttribute("dtoConversionTime");
            Boolean dtoUsed = (Boolean) request.getAttribute("dtoUsed");

            // 🔥 DEBUG: Log if DTO was used
            if (dtoUsed != null && dtoUsed && dtoConversionTime != null) {
                log.debug("DTO conversion detected: {} ms for endpoint: {} {}",
                        dtoConversionTime, method, endpoint);
            }

            tracker.recordMetric(endpoint, method, entityName, executionTime,
                    success, errorType, memoryDeltaKb, dtoConversionTime,
                    dtoUsed != null && dtoUsed);
        }
    }

    private String extractEntityName(Class<?> controllerClass) {
        java.lang.reflect.Type genericSuperclass = controllerClass.getGenericSuperclass();
        if (genericSuperclass instanceof java.lang.reflect.ParameterizedType paramType) {
            java.lang.reflect.Type[] typeArgs = paramType.getActualTypeArguments();
            if (typeArgs.length > 0 && typeArgs[0] instanceof Class<?> entityClass) {
                return entityClass.getSimpleName();
            }
        }
        return "Unknown";
    }
}
