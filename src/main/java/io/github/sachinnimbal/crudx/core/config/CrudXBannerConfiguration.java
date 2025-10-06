package io.github.sachinnimbal.crudx.core.config;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;


/**
 * @author Sachin Nimbal
 * @version 1.0.0
 * @since 2025
 * @Contact: <a href="mailto:sachinnimbal9@gmail.com">sachinnimbal9@gmail.com</a>
 * @see <a href="https://www.linkedin.com/in/sachin-nimbal/">LinkedIn Profile</a>
 */
@Configuration
public class CrudXBannerConfiguration implements ApplicationListener<ApplicationReadyEvent> {

    private final Environment environment;

    private static final String RESET = "\u001B[0m";
    private static final String RED = "\u001B[31m";
    private static final String GREEN = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String CYAN = "\u001B[36m";
    private static final String BOLD = "\u001B[1m";

    private static final long STARTUP_TIME = System.currentTimeMillis();

    public CrudXBannerConfiguration(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        printDatabaseInfo();
        printStartupTime();
    }

    private void printDatabaseInfo() {
        logInfo(CYAN + "--------------------------------------" + RESET);
        logInfo(BOLD + "  Database Configuration Status" + RESET);
        logInfo(CYAN + "--------------------------------------" + RESET);

        boolean mongoEnabled = isMongoEnabled();
        boolean mysqlEnabled = isMySqlEnabled();
        boolean postgresEnabled = isPostgresEnabled();

        logInfo(formatStatus("MongoDB Support", mongoEnabled));
        logInfo(formatStatus("MySQL Support", mysqlEnabled));
        logInfo(formatStatus("PostgreSQL Support", postgresEnabled));

        logInfo(CYAN + "--------------------------------------" + RESET);
        logInfo(YELLOW + "Note: Configure only one database provider" + RESET);
        logInfo(YELLOW + " -> Use either MongoDB OR MySQL/PostgreSQL" + RESET);
        logInfo("");
        logInfo(GREEN + BOLD + ">> CRUDX Framework is ready!" + RESET);
        logInfo(CYAN + ">> Documentation: " + YELLOW + "API Documentation (https://github.com/sachinnimbal/crudx-starter/blob/main/API_DOCUMENTATION.md)" + RESET);
        logInfo(CYAN + ">> Performance Dashboard: " + YELLOW + getDashboardUrl() + RESET);
    }

    private String formatStatus(String name, boolean isActive) {
        String status = isActive ? GREEN + "[CONFIGURED]" : RED + "[NOT CONFIGURED]";
        return " -> " + String.format("%-20s", name) + ": " + status + RESET;
    }

    private String getDashboardUrl() {
        String port = environment.getProperty("server.port", "8080");
        String contextPath = environment.getProperty("server.servlet.context-path", "");
        String host = environment.getProperty("server.address", "localhost");

        if (contextPath.endsWith("/")) {
            contextPath = contextPath.substring(0, contextPath.length() - 1);
        }

        return String.format("http://%s:%s%s/crudx/performance/dashboard", host, port, contextPath);
    }

    private void printStartupTime() {
        long end = System.currentTimeMillis();
        double durationSec = (end - STARTUP_TIME) / 1000.0;
        logInfo(String.format(YELLOW + BOLD + ">> Application started in %.3f seconds" + RESET, durationSec));
        logInfo("");
    }

    private void logInfo(String message) {
        System.out.println(message);
    }

    private boolean isMongoEnabled() {
        try {
            String mongoUri = environment.getProperty("spring.data.mongodb.uri");
            String mongoDatabase = environment.getProperty("spring.data.mongodb.database");
            return mongoUri != null || mongoDatabase != null;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isMySqlEnabled() {
        try {
            String driver = environment.getProperty("spring.datasource.driver-class-name");
            return driver != null && driver.contains("mysql");
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isPostgresEnabled() {
        try {
            String driver = environment.getProperty("spring.datasource.driver-class-name");
            return driver != null && driver.contains("postgresql");
        } catch (Exception e) {
            return false;
        }
    }
}