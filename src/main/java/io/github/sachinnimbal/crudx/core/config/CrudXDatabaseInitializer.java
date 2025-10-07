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

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * @author Sachin Nimbal
 * @see <a href="https://www.linkedin.com/in/sachin-nimbal/">LinkedIn Profile</a>
 */
@Slf4j
public class CrudXDatabaseInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    private static final String RESET = "\u001B[0m";
    private static final String BOLD = "\u001B[1m";
    private static final String RED = "\u001B[31m";
    private static final String GREEN = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String CYAN = "\u001B[36m";
    private static final String WHITE = "\u001B[37m";

    private static final String MYSQL_DRIVER = "com.mysql.cj.jdbc.Driver";
    private static final String POSTGRES_DRIVER = "org.postgresql.Driver";
    private static final String MONGODB_DRIVER = "com.mongodb.MongoClientSettings";

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        Environment env = applicationContext.getEnvironment();
        List<String> availableDrivers = detectAvailableDrivers();
        printSimpleBanner();

        // CRITICAL: Exit immediately if no drivers found
        if (availableDrivers.isEmpty()) {
            printNoDriverError();
            System.exit(1); // Exit with error code
        }

        String sqlUrl = env.getProperty("spring.datasource.url");
        String mongoUri = env.getProperty("spring.data.mongodb.uri");
        boolean hasSqlConfig = isConfigured(sqlUrl);
        boolean hasMongoConfig = isConfigured(mongoUri);

        // CRITICAL: Exit if drivers exist but no configuration
        if (!hasSqlConfig && !hasMongoConfig) {
            logError(buildNoConfigErrorMessage(availableDrivers));
            System.exit(1); // Exit with error code
        }

        validateDriverConfigurationMapping(availableDrivers, hasSqlConfig, hasMongoConfig);
        logActiveConfigurations(hasSqlConfig, hasMongoConfig, sqlUrl, mongoUri);

        if (hasSqlConfig) {
            handleSqlDatabaseInitialization(env, sqlUrl);
        }

        if (hasMongoConfig) {
            System.out.println(CYAN + "MongoDB config detected - will validate on first use" + RESET);
        }
    }

    private void printNoDriverError() {
        System.out.println("\n");
        System.out.println(RED + "===============================================" + RESET);
        System.out.println(RED + BOLD + "   NO DATABASE DRIVER FOUND" + RESET);
        System.out.println(RED + "===============================================" + RESET);
        System.out.println(YELLOW + "No supported database drivers detected in classpath!" + RESET);
        System.out.println("\n");
        System.out.println(CYAN + "To continue, add any one of the following dependencies:" + RESET);
        System.out.println(GREEN + "  → MySQL:      com.mysql:mysql-connector-j:8.3.0" + RESET);
        System.out.println(GREEN + "  → PostgreSQL: org.postgresql:postgresql:42.7.3" + RESET);
        System.out.println(GREEN + "  → MongoDB:    org.springframework.boot:spring-boot-starter-data-mongodb" + RESET);
        System.out.println(RED + "===============================================" + RESET);
        System.out.println(RED + BOLD + "\nApplication startup aborted." + RESET);
    }

    private String getVersion() {
        Package pkg = this.getClass().getPackage();
        String implVersion = pkg.getImplementationVersion();
        return implVersion != null ? implVersion : "0.0.1-SNAPSHOT";
    }

    public void printSimpleBanner() {
        logInfo(CYAN + "============================================" + RESET);
        logInfo(GREEN + " :: CRUDX Framework ::    " + RESET + BOLD + "(v" + getVersion() + ")" + RESET);
        logInfo(CYAN + "||" + RESET + BOLD + "  Lightweight & High-Performance CRUD  " + RESET + CYAN + "||" + RESET);
        logInfo(CYAN + "============================================" + RESET);
    }

    private List<String> detectAvailableDrivers() {
        List<String> drivers = new ArrayList<>();
        if (isClassPresent(MYSQL_DRIVER)) drivers.add("MySQL");
        if (isClassPresent(POSTGRES_DRIVER)) drivers.add("PostgreSQL");
        if (isClassPresent(MONGODB_DRIVER)) drivers.add("MongoDB");
        return drivers;
    }

    private boolean isClassPresent(String className) {
        try {
            Class.forName(className, false, getClass().getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private void validateDriverConfigurationMapping(List<String> availableDrivers,
                                                    boolean hasSqlConfig,
                                                    boolean hasMongoConfig) {
        boolean anyDatabaseConfigured = hasSqlConfig || hasMongoConfig;

        if (!anyDatabaseConfigured) {
            List<String> missingConfigs = new ArrayList<>(availableDrivers);
            logError(buildMissingConfigErrorMessage(missingConfigs));
            System.exit(1); // Exit immediately
        }
    }

    private void validateSqlConnection(Environment env, String url) {
        String username = env.getProperty("spring.datasource.username");
        String password = env.getProperty("spring.datasource.password");
        String driver = env.getProperty("spring.datasource.driver-class-name");

        if (driver == null) {
            logError("\n" + RED + "ERROR: spring.datasource.driver-class-name is required" + RESET);
            System.exit(1);
        }

        try {
            Class.forName(driver);
            try (Connection conn = DriverManager.getConnection(url, username, password)) {
                System.out.println(GREEN + "[OK] SQL connection validated" + RESET);
            }
        } catch (ClassNotFoundException e) {
            String dbType = detectSqlDatabaseType(url);
            logError(buildDriverNotFoundErrorMessage(dbType, driver, url));
            System.exit(1); // Exit on driver not found
        } catch (Exception e) {
            logError(buildConnectionErrorMessage(url, e.getMessage()));
            System.exit(1); // Exit on connection failure
        }
    }

    private void handleSqlDatabaseInitialization(Environment env, String url) {
        Boolean autoCreate = env.getProperty("crudx.database.auto-create", Boolean.class, true);
        String databaseName = extractDatabaseName(url);

        if (databaseName == null) {
            logWarning("Could not extract database name from URL: " + maskPassword(url));
            return;
        }

        if (!autoCreate) {
            System.out.println(YELLOW + "Database auto-creation disabled" + RESET);
            try {
                validateSqlConnection(env, url);
            } catch (Exception e) {
                String dbType = detectSqlDatabaseType(url);
                logError(buildManualDatabaseCreationMessage(dbType, databaseName, url));
                System.exit(1); // Exit if database doesn't exist
            }
            return;
        }

        try {
            createDatabaseIfNotExists(env, url, databaseName);
        } catch (Exception e) {
            logError(buildConnectionErrorMessage(url, e.getMessage()));
            System.exit(1); // Exit on database creation failure
        }
    }

    private void createDatabaseIfNotExists(Environment env, String originalUrl, String dbName) {
        String username = env.getProperty("spring.datasource.username");
        String password = env.getProperty("spring.datasource.password");
        String driver = env.getProperty("spring.datasource.driver-class-name");

        if (driver == null) {
            logError("\n" + RED + "ERROR: spring.datasource.driver-class-name is required" + RESET);
            System.exit(1);
        }

        try {
            Class.forName(driver);
            System.out.println(CYAN + "Checking/creating database '" + dbName + "'..." + RESET);

            if (driver.contains("mysql")) {
                createMySQLDatabaseIfNeeded(originalUrl, username, password, dbName);
            } else if (driver.contains("postgresql")) {
                createPostgreSQLDatabaseIfNeeded(originalUrl, username, password, dbName);
            } else {
                logWarning("Unsupported driver for auto-creation: " + driver);
                validateConnectionWithDatabase(originalUrl, username, password);
            }
        } catch (ClassNotFoundException e) {
            String dbType = detectSqlDatabaseType(originalUrl);
            logError(buildDriverNotFoundErrorMessage(dbType, driver, originalUrl));
            System.exit(1); // Exit on driver not found
        } catch (Exception e) {
            logError("\n" + RED + "Failed to connect to database server: " + e.getMessage() + RESET);
            System.exit(1); // Exit on connection failure
        }
    }

    private void createMySQLDatabaseIfNeeded(String originalUrl, String username, String password, String dbName)
            throws Exception {
        String baseUrl = originalUrl.substring(0, originalUrl.lastIndexOf("/"));

        try (Connection conn = DriverManager.getConnection(baseUrl, username, password);
             Statement stmt = conn.createStatement()) {

            String createDbSql = String.format(
                    "CREATE DATABASE IF NOT EXISTS `%s` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci",
                    dbName
            );
            stmt.executeUpdate(createDbSql);
            System.out.println(GREEN + "[OK] MySQL database '" + dbName + "' created/verified" + RESET);
        }
    }

    private void createPostgreSQLDatabaseIfNeeded(String originalUrl, String username, String password, String dbName)
            throws Exception {
        String baseUrl = originalUrl.substring(0, originalUrl.lastIndexOf("/"));
        String postgresUrl = baseUrl + "/postgres";

        try (Connection conn = DriverManager.getConnection(postgresUrl, username, password)) {
            String checkSql = "SELECT 1 FROM pg_database WHERE datname = ?";

            try (var prepStmt = conn.prepareStatement(checkSql)) {
                prepStmt.setString(1, dbName);
                try (ResultSet rs = prepStmt.executeQuery()) {
                    if (!rs.next()) {
                        conn.setAutoCommit(true);
                        try (Statement createStmt = conn.createStatement()) {
                            String createDbSql = String.format(
                                    "CREATE DATABASE \"%s\" WITH ENCODING 'UTF8'",
                                    dbName.replace("\"", "\"\"")
                            );
                            createStmt.executeUpdate(createDbSql);
                            System.out.println(GREEN + "[OK] PostgreSQL database '" + dbName + "' created" + RESET);
                        }
                    } else {
                        System.out.println(GREEN + "[OK] PostgreSQL database '" + dbName + "' exists" + RESET);
                    }
                }
            }
        }
    }

    private void validateConnectionWithDatabase(String url, String username, String password) throws Exception {
        try (Connection conn = DriverManager.getConnection(url, username, password)) {
            System.out.println(GREEN + "[OK] Database connection validated" + RESET);
        }
    }

    private void logActiveConfigurations(boolean hasSqlConfig, boolean hasMongoConfig, String sqlUrl, String mongoUri) {
        System.out.println(CYAN + "----------------------------------------" + RESET);
        System.out.println(BOLD + "  Active Database Configurations" + RESET);
        System.out.println(CYAN + "----------------------------------------" + RESET);

        if (hasSqlConfig) {
            String dbType = detectSqlDatabaseType(sqlUrl);
            System.out.println(GREEN + "  [OK] SQL Database (" + BOLD + dbType + RESET + GREEN + ")" + RESET);
            System.out.println("       URL: " + truncate(maskPassword(sqlUrl), 50));
        }

        if (hasMongoConfig) {
            System.out.println(GREEN + "  [OK] MongoDB Database" + RESET);
            System.out.println("       URI: " + truncate(maskPassword(mongoUri), 50));
        }

        System.out.println(CYAN + "----------------------------------------" + RESET);
    }

    private void logError(String message) {
        System.out.println(message);
    }

    private void logWarning(String message) {
        log.warn(YELLOW + "{}" + RESET, message);
    }

    private String buildNoConfigErrorMessage(List<String> availableDrivers) {
        StringBuilder msg = new StringBuilder();
        msg.append("\n");
        msg.append(RED + "===============================================\n" + RESET);
        msg.append(RED + BOLD + "   DATABASE CONFIGURATION ERROR\n" + RESET);
        msg.append(RED + "===============================================\n" + RESET);
        msg.append(RED + "Database driver(s) found but not configured!\n" + RESET);
        msg.append("\n");
        msg.append(CYAN + "Detected drivers:\n" + RESET);

        for (String driver : availableDrivers) {
            msg.append(WHITE + "  -> " + driver + "\n" + RESET);
        }

        msg.append("\n");
        msg.append(YELLOW + "Please configure at least one database:\n" + RESET);
        msg.append("\n");

        appendDatabaseConfigExamples(msg, availableDrivers);

        msg.append(RED + "===============================================\n" + RESET);
        msg.append(RED + BOLD + "Application startup aborted.\n" + RESET);
        return msg.toString();
    }

    private String buildMissingConfigErrorMessage(List<String> missingConfigs) {
        StringBuilder msg = new StringBuilder();
        msg.append("\n");
        msg.append(RED + "===============================================\n" + RESET);
        msg.append(RED + BOLD + "   DRIVER-CONFIGURATION MISMATCH\n" + RESET);
        msg.append(RED + "===============================================\n" + RESET);
        msg.append("Driver(s) in classpath but config missing:\n\n");

        for (String driver : missingConfigs) {
            msg.append(RED + "  [X] " + driver + "\n" + RESET);
        }

        msg.append("\n");
        msg.append(YELLOW + "Add configuration or remove dependency:\n" + RESET);
        msg.append("\n");

        appendDatabaseConfigExamples(msg, missingConfigs);

        msg.append(YELLOW + "OR remove unused dependency from build.gradle/pom.xml\n" + RESET);
        msg.append(RED + "===============================================\n" + RESET);
        msg.append(RED + BOLD + "Application startup aborted.\n" + RESET);
        return msg.toString();
    }

    private String buildManualDatabaseCreationMessage(String dbType, String dbName, String url) {
        StringBuilder msg = new StringBuilder();
        msg.append("\n");
        msg.append(RED + "===============================================\n" + RESET);
        msg.append(RED + BOLD + "   DATABASE MANUAL CREATION REQUIRED\n" + RESET);
        msg.append(RED + "===============================================\n" + RESET);
        msg.append("Auto-creation disabled (crudx.database.auto-create=false)\n");
        msg.append(RED + "Database '" + dbName + "' does not exist!\n" + RESET);
        msg.append("\n");
        msg.append(CYAN + "Database Type: " + dbType + "\n" + RESET);
        msg.append(CYAN + "Connection URL: " + maskPassword(url) + "\n" + RESET);
        msg.append("\n");

        appendManualCreationInstructions(msg, dbType, dbName);

        msg.append("\n");
        msg.append(CYAN + "Alternative: Enable auto-creation\n" + RESET);
        msg.append("  crudx.database.auto-create=true\n");
        msg.append(RED + "===============================================\n" + RESET);
        msg.append(RED + BOLD + "Application startup aborted.\n" + RESET);
        return msg.toString();
    }

    private String buildDriverNotFoundErrorMessage(String dbType, String driverClass, String url) {
        StringBuilder msg = new StringBuilder();
        msg.append("\n");
        msg.append(RED + "===============================================\n" + RESET);
        msg.append(RED + BOLD + "   DATABASE DRIVER NOT FOUND\n" + RESET);
        msg.append(RED + "===============================================\n" + RESET);
        msg.append("Database config found but driver missing!\n\n");
        msg.append(CYAN + "Database Type: " + dbType + "\n" + RESET);
        msg.append(CYAN + "Driver Class: " + driverClass + "\n" + RESET);
        msg.append(CYAN + "Connection URL: " + maskPassword(url) + "\n" + RESET);
        msg.append("\n");
        msg.append(YELLOW + "You have TWO options:\n" + RESET);
        msg.append("\n");
        msg.append(GREEN + "OPTION 1: Add database driver dependency\n" + RESET);
        msg.append("\n");

        appendDriverDependencyInstructions(msg, dbType);

        msg.append("\n");
        msg.append(GREEN + "OPTION 2: Remove database configuration\n" + RESET);
        msg.append("\nRemove from application.yml/application.properties:\n");
        msg.append("  spring.datasource.url\n");
        msg.append("  spring.datasource.username\n");
        msg.append("  spring.datasource.password\n");
        msg.append("  spring.datasource.driver-class-name\n");
        msg.append(RED + "===============================================\n" + RESET);
        msg.append(RED + BOLD + "Application startup aborted.\n" + RESET);
        return msg.toString();
    }

    private String buildConnectionErrorMessage(String url, String error) {
        return "\n" +
                RED + "===============================================\n" + RESET +
                RED + BOLD + "   SQL DATABASE CONNECTION ERROR\n" + RESET +
                RED + "===============================================\n" + RESET +
                RED + "Failed to connect to SQL database\n" + RESET +
                "\n" +
                RED + "Error: " + truncate(error, 60) + "\n" + RESET +
                "\n" +
                YELLOW + "Please verify:\n" + RESET +
                "  -> Database server is running\n" +
                "  -> Connection URL is correct\n" +
                "  -> Username and password are valid\n" +
                "  -> Network connectivity\n" +
                "\n" +
                CYAN + "Configuration:\n" + RESET +
                "  URL: " + maskPassword(url) + "\n" +
                RED + "===============================================\n" + RESET +
                RED + BOLD + "Application startup aborted.\n" + RESET;
    }

    private void appendDatabaseConfigExamples(StringBuilder msg, List<String> databases) {
        if (databases.contains("MySQL")) {
            msg.append(GREEN + "For MySQL:\n" + RESET);
            msg.append("  spring.datasource.url=jdbc:mysql://localhost:3306/dbname\n");
            msg.append("  spring.datasource.username=root\n");
            msg.append("  spring.datasource.password=password\n");
            msg.append("  spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver\n\n");
        }

        if (databases.contains("PostgreSQL")) {
            msg.append(GREEN + "For PostgreSQL:\n" + RESET);
            msg.append("  spring.datasource.url=jdbc:postgresql://localhost:5432/db\n");
            msg.append("  spring.datasource.username=postgres\n");
            msg.append("  spring.datasource.password=password\n");
            msg.append("  spring.datasource.driver-class-name=org.postgresql.Driver\n\n");
        }

        if (databases.contains("MongoDB")) {
            msg.append(GREEN + "For MongoDB:\n" + RESET);
            msg.append("  spring.data.mongodb.uri=mongodb://localhost:27017/dbname\n\n");
        }
    }

    private void appendManualCreationInstructions(StringBuilder msg, String dbType, String dbName) {
        if ("MySQL".equals(dbType)) {
            msg.append(YELLOW + "Create database manually using MySQL CLI:\n" + RESET);
            msg.append("\n");
            msg.append(GREEN + "Step 1: Connect to MySQL\n" + RESET);
            msg.append("  mysql -u root -p\n\n");
            msg.append(GREEN + "Step 2: Create database\n" + RESET);
            msg.append("  CREATE DATABASE `" + dbName + "`\n");
            msg.append("  CHARACTER SET utf8mb4\n");
            msg.append("  COLLATE utf8mb4_unicode_ci;\n\n");
            msg.append(GREEN + "Step 3: Verify\n" + RESET);
            msg.append("  SHOW DATABASES;\n\n");
            msg.append(GREEN + "Step 4: Grant permissions (if needed)\n" + RESET);
            msg.append("  GRANT ALL PRIVILEGES ON `" + dbName + "`.* TO 'user'@'%';\n");
            msg.append("  FLUSH PRIVILEGES;\n");
        } else if ("PostgreSQL".equals(dbType)) {
            msg.append(YELLOW + "Create database manually using PostgreSQL CLI:\n" + RESET);
            msg.append("\n");
            msg.append(GREEN + "Step 1: Connect to PostgreSQL\n" + RESET);
            msg.append("  psql -U postgres\n\n");
            msg.append(GREEN + "Step 2: Create database\n" + RESET);
            msg.append("  CREATE DATABASE \"" + dbName + "\" WITH ENCODING 'UTF8';\n\n");
            msg.append(GREEN + "Step 3: Verify\n" + RESET);
            msg.append("  \\l\n\n");
            msg.append(GREEN + "Step 4: Grant permissions (if needed)\n" + RESET);
            msg.append("  GRANT ALL PRIVILEGES ON DATABASE \"" + dbName + "\" TO user;\n");
        }
    }

    private void appendDriverDependencyInstructions(StringBuilder msg, String dbType) {
        if ("MySQL".equals(dbType)) {
            msg.append("For Gradle (build.gradle):\n");
            msg.append("  runtimeOnly 'com.mysql:mysql-connector-j:8.3.0'\n\n");
            msg.append("For Maven (pom.xml):\n");
            msg.append("  <dependency>\n");
            msg.append("    <groupId>com.mysql</groupId>\n");
            msg.append("    <artifactId>mysql-connector-j</artifactId>\n");
            msg.append("    <version>8.3.0</version>\n");
            msg.append("    <scope>runtime</scope>\n");
            msg.append("  </dependency>\n");
        } else if ("PostgreSQL".equals(dbType)) {
            msg.append("For Gradle (build.gradle):\n");
            msg.append("  runtimeOnly 'org.postgresql:postgresql:42.7.3'\n\n");
            msg.append("For Maven (pom.xml):\n");
            msg.append("  <dependency>\n");
            msg.append("    <groupId>org.postgresql</groupId>\n");
            msg.append("    <artifactId>postgresql</artifactId>\n");
            msg.append("    <version>42.7.3</version>\n");
            msg.append("    <scope>runtime</scope>\n");
            msg.append("  </dependency>\n");
        }
    }

    private boolean isConfigured(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String extractDatabaseName(String url) {
        try {
            if (url.contains("?")) {
                url = url.substring(0, url.indexOf("?"));
            }
            String[] parts = url.split("/");
            if (parts.length > 0) {
                return parts[parts.length - 1];
            }
        } catch (Exception e) {
            log.error("Error extracting database name", e);
        }
        return null;
    }

    private String detectSqlDatabaseType(String url) {
        if (url.contains("mysql")) return "MySQL";
        if (url.contains("postgresql")) return "PostgreSQL";
        if (url.contains("oracle")) return "Oracle";
        if (url.contains("sqlserver")) return "SQL Server";
        return "Unknown";
    }

    private void logInfo(String message) {
        System.out.println(message);
    }

    private String maskPassword(String url) {
        if (url == null) return "null";
        return url.replaceAll(":[^:@]+@", ":****@");
    }

    private String truncate(String str, int maxLength) {
        if (str == null) return "";
        if (str.length() <= maxLength) return str;
        return str.substring(0, maxLength - 3) + "...";
    }
}
