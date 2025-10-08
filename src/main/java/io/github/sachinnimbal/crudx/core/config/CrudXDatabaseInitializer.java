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

        // Get configured databases
        String sqlUrl = env.getProperty("spring.datasource.url");
        String mongoUri = env.getProperty("spring.data.mongodb.uri");
        boolean hasSqlConfig = isConfigured(sqlUrl);
        boolean hasMongoConfig = isConfigured(mongoUri);

        // Log active configurations
        logActiveConfigurations(hasSqlConfig, hasMongoConfig, sqlUrl, mongoUri, availableDrivers);

        // Validate and initialize SQL if configured
        if (hasSqlConfig) {
            handleSqlDatabaseInitialization(env, sqlUrl);
        }

        // Validate MongoDB if configured (connection will be tested on first use)
        if (hasMongoConfig) {
            System.out.println(CYAN + "MongoDB config detected - connection will be validated on first use" + RESET);
        }
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
                logError(buildConnectionErrorMessage(url, e.getMessage()));
                System.exit(1);
            }
            return;
        }

        try {
            createDatabaseIfNotExists(env, url, databaseName);
        } catch (Exception e) {
            logError(buildConnectionErrorMessage(url, e.getMessage()));
            System.exit(1);
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
                System.out.println(GREEN + "[OK] SQL connection validated successfully" + RESET);
            }
        } catch (ClassNotFoundException e) {
            String dbType = detectSqlDatabaseType(url);
            logError(buildDriverNotFoundErrorMessage(dbType, driver, url));
            System.exit(1);
        } catch (Exception e) {
            throw new RuntimeException("Connection failed: " + e.getMessage(), e);
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
            System.exit(1);
        } catch (Exception e) {
            logError("\n" + RED + "Failed to connect to database server!" + RESET);
            logError(RED + "Error: " + e.getMessage() + RESET);
            logError("\n" + YELLOW + "Please check:" + RESET);
            logError("  1. Database server is running");
            logError("  2. Host and port are correct");
            logError("  3. Username and password are valid");
            logError("  4. Network connectivity");
            System.exit(1);
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

    private void logActiveConfigurations(boolean hasSqlConfig, boolean hasMongoConfig,
                                         String sqlUrl, String mongoUri, List<String> availableDrivers) {
        System.out.println(CYAN + "----------------------------------------" + RESET);
        System.out.println(BOLD + "  Database Configuration Status" + RESET);
        System.out.println(CYAN + "----------------------------------------" + RESET);

        System.out.println(WHITE + "Available Drivers:" + RESET);
        if (availableDrivers.isEmpty()) {
            System.out.println(RED + "  [X] No drivers found" + RESET);
        } else {
            for (String driver : availableDrivers) {
                System.out.println(GREEN + "  [✓] " + driver + RESET);
            }
        }

        System.out.println();
        System.out.println(WHITE + "Configured Databases:" + RESET);

        if (hasSqlConfig) {
            String dbType = detectSqlDatabaseType(sqlUrl);
            System.out.println(GREEN + "  [✓] SQL Database (" + BOLD + dbType + RESET + GREEN + ")" + RESET);
            System.out.println("       URL: " + truncate(maskPassword(sqlUrl), 50));
        } else {
            if (availableDrivers.contains("MySQL") || availableDrivers.contains("PostgreSQL")) {
                System.out.println(YELLOW + "  [!] SQL driver available but not configured" + RESET);
            }
        }

        if (hasMongoConfig) {
            System.out.println(GREEN + "  [✓] MongoDB Database" + RESET);
            System.out.println("       URI: " + truncate(maskPassword(mongoUri), 50));
        } else {
            if (availableDrivers.contains("MongoDB")) {
                System.out.println(YELLOW + "  [!] MongoDB driver available but not configured" + RESET);
            }
        }

        if (!hasSqlConfig && !hasMongoConfig) {
            System.out.println(RED + "  [X] No databases configured" + RESET);
        }

        System.out.println(CYAN + "----------------------------------------" + RESET);
    }

    private void logError(String message) {
        System.out.println(message);
    }

    private void logWarning(String message) {
        System.out.println(YELLOW + message + RESET);
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
        msg.append(YELLOW + "Add the appropriate driver dependency:\n" + RESET);
        msg.append("\n");

        appendDriverDependencyInstructions(msg, dbType);

        msg.append(RED + "===============================================\n" + RESET);
        return msg.toString();
    }

    private String buildConnectionErrorMessage(String url, String error) {
        return "\n" +
                RED + "===============================================\n" + RESET +
                RED + BOLD + "   DATABASE CONNECTION ERROR\n" + RESET +
                RED + "===============================================\n" + RESET +
                RED + "Failed to connect to database!\n" + RESET +
                "\n" +
                RED + "Error: " + truncate(error, 60) + "\n" + RESET +
                "\n" +
                YELLOW + "Please verify:\n" + RESET +
                "  1. Database server is RUNNING\n" +
                "  2. Connection URL is correct\n" +
                "  3. Username and password are valid\n" +
                "  4. Network/firewall allows connection\n" +
                "  5. Database exists (or enable auto-create)\n" +
                "\n" +
                CYAN + "Configuration:\n" + RESET +
                "  URL: " + maskPassword(url) + "\n" +
                RED + "===============================================\n" + RESET;
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
            msg.append("  SHOW DATABASES;\n");
        } else if ("PostgreSQL".equals(dbType)) {
            msg.append(YELLOW + "Create database manually using PostgreSQL CLI:\n" + RESET);
            msg.append("\n");
            msg.append(GREEN + "Step 1: Connect to PostgreSQL\n" + RESET);
            msg.append("  psql -U postgres\n\n");
            msg.append(GREEN + "Step 2: Create database\n" + RESET);
            msg.append("  CREATE DATABASE \"" + dbName + "\" WITH ENCODING 'UTF8';\n\n");
            msg.append(GREEN + "Step 3: Verify\n" + RESET);
            msg.append("  \\l\n");
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
        if (url == null) return "Unknown";
        if (url.contains("mysql")) return "MySQL";
        if (url.contains("postgresql")) return "PostgreSQL";
        if (url.contains("oracle")) return "Oracle";
        if (url.contains("sqlserver")) return "SQL Server";
        if (url.contains("h2")) return "H2";
        return "Unknown";
    }

    private void logInfo(String message) {
        System.out.println(message);
    }

    private String maskPassword(String url) {
        if (url == null) return "null";
        return url.replaceAll(":[^:@]+@", ":****@")
                .replaceAll("password=([^&;]+)", "password=****");
    }

    private String truncate(String str, int maxLength) {
        if (str == null) return "";
        if (str.length() <= maxLength) return str;
        return str.substring(0, maxLength - 3) + "...";
    }
}