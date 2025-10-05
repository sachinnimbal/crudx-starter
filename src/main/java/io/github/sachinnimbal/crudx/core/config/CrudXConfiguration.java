package io.github.sachinnimbal.crudx.core.config;

import io.github.sachinnimbal.crudx.core.exception.CrudXGlobalExceptionHandler;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.mongodb.config.EnableMongoAuditing;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;


/**
 * @author Sachin Nimbal
 * @version 1.0.0-SNAPSHOT
 * @since 2025
 * @Contact: <a href="mailto:sachinnimbal9@gmail.com">sachinnimbal9@gmail.com</a>
 * @see <a href="https://www.linkedin.com/in/sachin-nimbal/">LinkedIn Profile</a>
 */
@Slf4j
@Configuration
@ComponentScan(basePackages = "io.github.sachinnimbal.crudx")
@Import({
        CrudXBannerConfiguration.class,
        CrudXServiceAutoConfiguration.class,
        CrudXGlobalExceptionHandler.class,
        CrudXPerformanceConfiguration.class
})
public class CrudXConfiguration {

    private static final String RESET = "\u001B[0m";
    private static final String BOLD = "\u001B[1m";
    private static final String RED = "\u001B[31m";
    private static final String GREEN = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String CYAN = "\u001B[36m";
    private static final String WHITE = "\u001B[37m";

    private final Environment environment;

    public CrudXConfiguration(Environment environment) {
        this.environment = environment;
        logFrameworkInitialization();
    }

    private void logFrameworkInitialization() {
        log.info(CYAN + "========================================" + RESET);
        log.info(BOLD + WHITE + "  CRUDX Framework Initialization" + RESET);
        log.info(GREEN + "  Zero-Boilerplate Service Generation" + RESET);
        log.info(CYAN + "========================================" + RESET);
    }

    @PostConstruct
    public void validateDatabaseConfiguration() {
        String sqlUrl = environment.getProperty("spring.datasource.url");
        String mongoUri = environment.getProperty("spring.data.mongodb.uri");

        boolean hasSqlConfig = sqlUrl != null && !sqlUrl.trim().isEmpty();
        boolean hasMongoConfig = mongoUri != null && !mongoUri.trim().isEmpty();

        if (!hasSqlConfig && !hasMongoConfig) {
            String errorMessage = buildNoDatabaseConfigError();
            System.err.println(errorMessage);
            throw new IllegalStateException("No database configuration found. Please configure at least one database.");
        }
    }

    private String buildNoDatabaseConfigError() {
        StringBuilder msg = new StringBuilder();
        msg.append("\n");
        msg.append(RED + "================================================\n" + RESET);
        msg.append(RED + BOLD + "     DATABASE CONFIGURATION ERROR\n" + RESET);
        msg.append(RED + "================================================\n" + RESET);
        msg.append(RED + "No database configuration found!\n" + RESET);
        msg.append("\n");
        msg.append(YELLOW + "Please configure at least one database:\n" + RESET);
        msg.append("\n");
        msg.append(GREEN + "For SQL (MySQL/PostgreSQL):\n" + RESET);
        msg.append("  spring.datasource.url=jdbc:mysql://localhost:3306/mydb\n");
        msg.append("  spring.datasource.username=root\n");
        msg.append("  spring.datasource.password=yourpassword\n");
        msg.append("  spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver\n");
        msg.append("\n");
        msg.append(GREEN + "For MongoDB:\n" + RESET);
        msg.append("  spring.data.mongodb.uri=mongodb://localhost:27017/mydb\n");
        msg.append("\n");
        msg.append(WHITE + "Add these to your application.properties or application.yml\n" + RESET);
        msg.append(RED + "================================================\n" + RESET);
        return msg.toString();
    }

    @Configuration
    @ConditionalOnProperty(prefix = "spring.data.mongodb", name = "uri")
    @EnableMongoRepositories(basePackages = {
            "io.github.sachinnimbal.crudx",
            "${crudx.mongo.repository.packages:}"
    })
    @EnableMongoAuditing
    public static class MongoConfiguration {

        private final Environment environment;

        public MongoConfiguration(Environment environment) {
            this.environment = environment;
            logMongoConfiguration();
        }

        private void logMongoConfiguration() {
            String mongoUri = environment.getProperty("spring.data.mongodb.uri");

            log.info(CYAN + "----------------------------------------" + RESET);
            log.info(BOLD + WHITE + "  MongoDB Configuration Active" + RESET);
            log.info(CYAN + "----------------------------------------" + RESET);
            log.info(GREEN + "  [OK] MongoTemplate configured" + RESET);
            log.info(GREEN + "  [OK] MongoDB Auditing enabled" + RESET);
            log.info(GREEN + "  [OK] MongoDB Repositories enabled" + RESET);
            log.info(CYAN + "  Connection: " + RESET + maskMongoUri(mongoUri));
            log.info(CYAN + "----------------------------------------" + RESET);
        }

        private String maskMongoUri(String uri) {
            if (uri == null) return "Not configured";
            return uri.replaceAll("://([^:]+):([^@]+)@", "://$1:****@");
        }
    }

    @Configuration
    @ConditionalOnProperty(prefix = "spring.datasource", name = "url")
    @EnableJpaRepositories(basePackages = {
            "io.github.sachinnimbal.crudx",
            "${crudx.jpa.repository.packages:}"
    })
    @EnableJpaAuditing
    @EntityScan(basePackages = {
            "io.github.sachinnimbal.crudx",
            "${crudx.jpa.entity.packages:}"
    })
    public static class JpaConfiguration {

        private final Environment environment;

        public JpaConfiguration(Environment environment) {
            this.environment = environment;
            logJpaConfiguration();
        }

        private void logJpaConfiguration() {
            String datasourceUrl = environment.getProperty("spring.datasource.url");
            String databaseType = detectDatabaseType(datasourceUrl);

            log.info(CYAN + "----------------------------------------" + RESET);
            log.info(BOLD + WHITE + "  JPA/SQL Configuration Active" + RESET);
            log.info(CYAN + "----------------------------------------" + RESET);
            log.info(GREEN + "  [OK] EntityManager configured" + RESET);
            log.info(GREEN + "  [OK] JPA Auditing enabled" + RESET);
            log.info(GREEN + "  [OK] JPA Repositories enabled" + RESET);
            log.info(CYAN + "  Database: " + RESET + databaseType);
            log.info(CYAN + "  URL: " + RESET + truncate(maskPassword(datasourceUrl), 50));
            log.info(CYAN + "----------------------------------------" + RESET);
        }

        @Bean
        public HibernatePropertiesCustomizer hibernateDDLCustomizer() {
            return (hibernateProperties) -> {
                String ddlAuto = environment.getProperty(
                        "spring.jpa.hibernate.ddl-auto",
                        "update"
                );

                hibernateProperties.put("hibernate.hbm2ddl.auto", ddlAuto);
                hibernateProperties.putIfAbsent("hibernate.format_sql",
                        environment.getProperty("spring.jpa.properties.hibernate.format_sql", "false"));
                hibernateProperties.putIfAbsent("hibernate.use_sql_comments",
                        environment.getProperty("spring.jpa.properties.hibernate.use_sql_comments", "false"));

                logHibernateConfiguration(ddlAuto);
            };
        }

        private void logHibernateConfiguration(String ddlAuto) {
            log.info(CYAN + "----------------------------------------" + RESET);
            log.info(BOLD + WHITE + "  Hibernate DDL Configuration" + RESET);
            log.info(CYAN + "----------------------------------------" + RESET);
            log.info(CYAN + "  DDL Mode: " + RESET + YELLOW + ddlAuto + RESET);
            log.info(CYAN + "  Action: " + RESET + getSchemaActionDescription(ddlAuto));
            log.info(CYAN + "----------------------------------------" + RESET);
        }

        private String getSchemaActionDescription(String ddlAuto) {
            return switch (ddlAuto.toLowerCase()) {
                case "create" -> "Dropped and recreated on startup";
                case "create-drop" -> "Created on startup, dropped on shutdown";
                case "update" -> "Updated automatically (non-destructive)";
                case "validate" -> "Validated only (no modifications)";
                case "none" -> "No automatic schema management";
                default -> "Unknown mode: " + ddlAuto;
            };
        }

        private String detectDatabaseType(String url) {
            if (url == null) return "Unknown";
            if (url.contains("mysql")) return "MySQL";
            if (url.contains("postgresql")) return "PostgreSQL";
            if (url.contains("oracle")) return "Oracle";
            if (url.contains("sqlserver")) return "SQL Server";
            if (url.contains("h2")) return "H2 (In-Memory)";
            return "SQL Database";
        }

        private String maskPassword(String url) {
            if (url == null) return "Not configured";
            return url.replaceAll("password=([^&;]+)", "password=****")
                    .replaceAll(":[^:@]+@", ":****@");
        }

        private String truncate(String str, int maxLength) {
            if (str == null || str.length() <= maxLength) {
                return str;
            }
            return str.substring(0, maxLength - 3) + "...";
        }
    }
}