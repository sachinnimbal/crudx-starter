/*
 * Copyright 2025 Sachin Nimbal
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.sachinnimbal.crudx.core.config;

import io.github.sachinnimbal.crudx.core.exception.CrudXGlobalExceptionHandler;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
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
 * @see <a href="https://www.linkedin.com/in/sachin-nimbal/">LinkedIn Profile</a>
 */
@Slf4j
@Configuration
@ComponentScan(basePackages = "io.github.sachinnimbal.crudx")
@EnableConfigurationProperties(CrudXProperties.class)
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
        frameworkInitialization();
    }

    private void frameworkInitialization() {
        logInfo(CYAN + "========================================" + RESET);
        logInfo(BOLD + WHITE + "  CRUDX Framework Initialization" + RESET);
        logInfo(GREEN + "  Zero-Boilerplate Service Generation" + RESET);
        logInfo(CYAN + "========================================" + RESET);
    }

    @PostConstruct
    public void validateDatabaseConfiguration() {
        String sqlUrl = environment.getProperty("spring.datasource.url");
        String mongoUri = environment.getProperty("spring.data.mongodb.uri");

        boolean hasSqlConfig = isConfigured(sqlUrl);
        boolean hasMongoConfig = isConfigured(mongoUri);

        // Check if ANY database driver is available
        boolean hasMySQLDriver = isClassPresent("com.mysql.cj.jdbc.Driver");
        boolean hasPostgresDriver = isClassPresent("org.postgresql.Driver");
        boolean hasMongoDriver = isClassPresent("com.mongodb.MongoClientSettings");

        boolean hasAnyDriver = hasMySQLDriver || hasPostgresDriver || hasMongoDriver;

        // CRITICAL: Exit if no drivers at all
        if (!hasAnyDriver) {
            String errorMessage = buildNoDriverError();
            logError(errorMessage);
            System.exit(1);
        }

        // CRITICAL: Exit if drivers exist but no configuration
        if (!hasSqlConfig && !hasMongoConfig) {
            String errorMessage = buildNoDatabaseConfigError(hasMySQLDriver, hasPostgresDriver, hasMongoDriver);
            logError(errorMessage);
            System.exit(1);
        }

        // Warn if SQL is configured but no SQL driver available
        if (hasSqlConfig && !hasMySQLDriver && !hasPostgresDriver) {
            String errorMessage = buildSqlDriverMissingError(sqlUrl);
            logError(errorMessage);
            System.exit(1);
        }

        // Warn if MongoDB is configured but driver not available
        if (hasMongoConfig && !hasMongoDriver) {
            String errorMessage = buildMongoDriverMissingError();
            logError(errorMessage);
            System.exit(1);
        }
    }

    private boolean isClassPresent(String className) {
        try {
            Class.forName(className, false, getClass().getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private boolean isConfigured(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String buildNoDriverError() {
        StringBuilder msg = new StringBuilder();
        msg.append("\n");
        msg.append(RED + "================================================\n" + RESET);
        msg.append(RED + BOLD + "     NO DATABASE DRIVER FOUND\n" + RESET);
        msg.append(RED + "================================================\n" + RESET);
        msg.append(RED + "No database drivers detected in classpath!\n" + RESET);
        msg.append("\n");
        msg.append(YELLOW + "Add at least one database driver dependency:\n" + RESET);
        msg.append("\n");
        msg.append(GREEN + "For MySQL:\n" + RESET);
        msg.append("  Gradle: runtimeOnly 'com.mysql:mysql-connector-j:8.3.0'\n");
        msg.append("  Maven:  <artifactId>mysql-connector-j</artifactId>\n");
        msg.append("\n");
        msg.append(GREEN + "For PostgreSQL:\n" + RESET);
        msg.append("  Gradle: runtimeOnly 'org.postgresql:postgresql:42.7.3'\n");
        msg.append("  Maven:  <artifactId>postgresql</artifactId>\n");
        msg.append("\n");
        msg.append(GREEN + "For MongoDB:\n" + RESET);
        msg.append("  Gradle: implementation 'org.springframework.boot:spring-boot-starter-data-mongodb'\n");
        msg.append("  Maven:  <artifactId>spring-boot-starter-data-mongodb</artifactId>\n");
        msg.append(RED + "================================================\n" + RESET);
        msg.append(RED + BOLD + "Application startup aborted.\n" + RESET);
        return msg.toString();
    }

    private String buildNoDatabaseConfigError(boolean hasMySQL, boolean hasPostgres, boolean hasMongo) {
        StringBuilder msg = new StringBuilder();
        msg.append("\n");
        msg.append(RED + "================================================\n" + RESET);
        msg.append(RED + BOLD + "     DATABASE CONFIGURATION ERROR\n" + RESET);
        msg.append(RED + "================================================\n" + RESET);
        msg.append(RED + "Database drivers found but no configuration!\n" + RESET);
        msg.append("\n");
        msg.append(CYAN + "Available drivers:\n" + RESET);
        if (hasMySQL) msg.append(GREEN + "  ✓ MySQL\n" + RESET);
        if (hasPostgres) msg.append(GREEN + "  ✓ PostgreSQL\n" + RESET);
        if (hasMongo) msg.append(GREEN + "  ✓ MongoDB\n" + RESET);
        msg.append("\n");
        msg.append(YELLOW + "Configure at least one database:\n" + RESET);
        msg.append("\n");

        if (hasMySQL || hasPostgres) {
            msg.append(GREEN + "For SQL (MySQL/PostgreSQL):\n" + RESET);
            msg.append("  spring.datasource.url=jdbc:mysql://localhost:3306/mydb\n");
            msg.append("  spring.datasource.username=root\n");
            msg.append("  spring.datasource.password=yourpassword\n");
            msg.append("  spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver\n");
            msg.append("\n");
        }

        if (hasMongo) {
            msg.append(GREEN + "For MongoDB:\n" + RESET);
            msg.append("  spring.data.mongodb.uri=mongodb://localhost:27017/mydb\n");
            msg.append("\n");
        }

        msg.append(WHITE + "Add these to your application.properties or application.yml\n" + RESET);
        msg.append(RED + "================================================\n" + RESET);
        msg.append(RED + BOLD + "Application startup aborted.\n" + RESET);
        return msg.toString();
    }

    private String buildSqlDriverMissingError(String url) {
        StringBuilder msg = new StringBuilder();
        msg.append("\n");
        msg.append(RED + "================================================\n" + RESET);
        msg.append(RED + BOLD + "     SQL DRIVER MISSING\n" + RESET);
        msg.append(RED + "================================================\n" + RESET);
        msg.append(RED + "SQL database configured but driver not found!\n" + RESET);
        msg.append("\n");
        msg.append(CYAN + "Configured URL: " + maskPassword(url) + "\n" + RESET);
        msg.append("\n");
        msg.append(YELLOW + "Add the appropriate SQL driver:\n" + RESET);
        msg.append("\n");
        msg.append(GREEN + "For MySQL:\n" + RESET);
        msg.append("  Gradle: runtimeOnly 'com.mysql:mysql-connector-j:8.3.0'\n");
        msg.append("\n");
        msg.append(GREEN + "For PostgreSQL:\n" + RESET);
        msg.append("  Gradle: runtimeOnly 'org.postgresql:postgresql:42.7.3'\n");
        msg.append(RED + "================================================\n" + RESET);
        msg.append(RED + BOLD + "Application startup aborted.\n" + RESET);
        return msg.toString();
    }

    private String buildMongoDriverMissingError() {
        StringBuilder msg = new StringBuilder();
        msg.append("\n");
        msg.append(RED + "================================================\n" + RESET);
        msg.append(RED + BOLD + "     MONGODB DRIVER MISSING\n" + RESET);
        msg.append(RED + "================================================\n" + RESET);
        msg.append(RED + "MongoDB configured but driver not found!\n" + RESET);
        msg.append("\n");
        msg.append(YELLOW + "Add MongoDB driver:\n" + RESET);
        msg.append("\n");
        msg.append(GREEN + "Gradle:\n" + RESET);
        msg.append("  implementation 'org.springframework.boot:spring-boot-starter-data-mongodb'\n");
        msg.append("\n");
        msg.append(GREEN + "Maven:\n" + RESET);
        msg.append("  <dependency>\n");
        msg.append("    <groupId>org.springframework.boot</groupId>\n");
        msg.append("    <artifactId>spring-boot-starter-data-mongodb</artifactId>\n");
        msg.append("  </dependency>\n");
        msg.append(RED + "================================================\n" + RESET);
        msg.append(RED + BOLD + "Application startup aborted.\n" + RESET);
        return msg.toString();
    }

    private void logInfo(String message) {
        System.out.println(message);
    }

    private void logError(String message) {
        System.out.println(message);
    }

    private String maskPassword(String url) {
        if (url == null) return "null";
        return url.replaceAll(":[^:@]+@", ":****@");
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

            logInfo(CYAN + "----------------------------------------" + RESET);
            logInfo(BOLD + WHITE + "  MongoDB Configuration Active" + RESET);
            logInfo(CYAN + "----------------------------------------" + RESET);
            logInfo(GREEN + "  [OK] MongoTemplate configured" + RESET);
            logInfo(GREEN + "  [OK] MongoDB Auditing enabled" + RESET);
            logInfo(GREEN + "  [OK] MongoDB Repositories enabled" + RESET);
            logInfo(CYAN + "  Connection: " + RESET + maskMongoUri(mongoUri));
            logInfo(CYAN + "----------------------------------------" + RESET);
        }

        private void logInfo(String message) {
            System.out.println(message);
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

            logInfo(CYAN + "----------------------------------------" + RESET);
            logInfo(BOLD + WHITE + "  JPA/SQL Configuration Active" + RESET);
            logInfo(CYAN + "----------------------------------------" + RESET);
            logInfo(GREEN + "  [OK] DataSource will be auto-configured" + RESET);
            logInfo(GREEN + "  [OK] EntityManager will be created" + RESET);
            logInfo(GREEN + "  [OK] JPA Auditing enabled" + RESET);
            logInfo(GREEN + "  [OK] JPA Repositories enabled" + RESET);
            logInfo(CYAN + "  Database: " + RESET + databaseType);
            logInfo(CYAN + "  URL: " + RESET + truncate(maskPassword(datasourceUrl), 50));
            logInfo(CYAN + "----------------------------------------" + RESET);
        }

        private void logInfo(String message) {
            System.out.println(message);
        }

        @Bean
        @ConditionalOnMissingBean
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
            logInfo(CYAN + "----------------------------------------" + RESET);
            logInfo(BOLD + WHITE + "  Hibernate DDL Configuration" + RESET);
            logInfo(CYAN + "----------------------------------------" + RESET);
            logInfo(CYAN + "  DDL Mode: " + RESET + YELLOW + ddlAuto + RESET);
            logInfo(CYAN + "  Action: " + RESET + getSchemaActionDescription(ddlAuto));
            logInfo(CYAN + "----------------------------------------" + RESET);
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