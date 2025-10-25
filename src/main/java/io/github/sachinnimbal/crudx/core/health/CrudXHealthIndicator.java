package io.github.sachinnimbal.crudx.core.health;

import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
@ConditionalOnProperty(prefix = "management.health.crudx", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CrudXHealthIndicator implements HealthIndicator {

    @Autowired(required = false)
    private EntityManager entityManager;

    @Autowired(required = false)
    private MongoTemplate mongoTemplate;

    @Override
    public Health health() {
        Map<String, Object> details = new HashMap<>();

        try {
            // Check SQL connection
            if (entityManager != null) {
                entityManager.createNativeQuery("SELECT 1").getSingleResult();
                details.put("sql", "UP");
            }

            // Check MongoDB connection
            if (mongoTemplate != null) {
                mongoTemplate.executeCommand("{ ping: 1 }");
                details.put("mongodb", "UP");
            }

            // Check memory
            Runtime runtime = Runtime.getRuntime();
            long usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024;
            long maxMemory = runtime.maxMemory() / 1024 / 1024;
            double memoryUsage = (double) usedMemory / maxMemory * 100;

            details.put("memoryUsedMB", usedMemory);
            details.put("memoryMaxMB", maxMemory);
            details.put("memoryUsagePercent", String.format("%.2f%%", memoryUsage));

            if (memoryUsage > 90) {
                return Health.down()
                        .withDetails(details)
                        .withDetail("warning", "High memory usage")
                        .build();
            }

            return Health.up()
                    .withDetails(details)
                    .build();

        } catch (Exception e) {
            return Health.down()
                    .withDetail("error", e.getMessage())
                    .withException(e)
                    .build();
        }
    }
}
