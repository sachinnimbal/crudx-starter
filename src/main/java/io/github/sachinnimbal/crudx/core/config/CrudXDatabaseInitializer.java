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
 * @version 1.0.0
 * @since 2025
 * @Contact: <a href="mailto:sachinnimbal9@gmail.com">sachinnimbal9@gmail.com</a>
 * @see <a href="https://www.linkedin.com/in/sachin-nimbal/">LinkedIn Profile</a>
 */
@Slf4j
public class CrudXDatabaseInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    // ANSI Color Codes - consistent with banner
    private static final String RESET = "\u001B[0m";
    private static final String BOLD = "\u001B[1m";
    private static final String RED = "\u001B[31m";
    private static final String GREEN = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String CYAN = "\u001B[36m";
    private static final String WHITE = "\u001B[37m";

    // Database Driver Constants
    private static final String MYSQL_DRIVER = "com.mysql.cj.jdbc.Driver";
    private static final String POSTGRES_DRIVER = "org.postgresql.Driver";
    private static final String MONGODB_DRIVER = "com.mongodb.MongoClientSettings";

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        Environment env = applicationContext.getEnvironment();

        List<String> availableDrivers = detectAvailableDrivers();

        if (availableDrivers.isEmpty()) {
            logWarning("No database drivers found in classpath");
            return;
        }

        String sqlUrl = env.getProperty("spring.datasource.url");
        String mongoUri = env.getProperty("spring.data.mongodb.uri");

        boolean hasSqlConfig = sqlUrl != null && !sqlUrl.trim().isEmpty();
        boolean hasMongoConfig = mongoUri != null && !mongoUri.trim().isEmpty();

        validateDriverConfigurationMapping(availableDrivers, hasSqlConfig, hasMongoConfig, sqlUrl);

        if (!hasSqlConfig && !hasMongoConfig) {
            String errorMessage = buildNoConfigErrorMessage(availableDrivers);
            logError(errorMessage);
            throw new IllegalStateException("Database drivers found but no configuration provided. Please configure at least one database.");
        }

        logActiveConfigurations(hasSqlConfig, hasMongoConfig, sqlUrl, mongoUri);

        if (hasSqlConfig) {
            try {
                handleSqlDatabaseInitialization(env, sqlUrl);
            } catch (Exception e) {
                String errorMessage = buildConnectionErrorMessage(sqlUrl, e.getMessage());
                logError(errorMessage);
                throw new IllegalStateException("SQL database server is not available or connection failed", e);
            }
        }

        if (hasMongoConfig) {
            validateMongoConnection(mongoUri);
        }
    }

    private List<String> detectAvailableDrivers() {
        List<String> drivers = new ArrayList<>();

        if (isClassPresent(MYSQL_DRIVER)) {
            drivers.add("MySQL");
        }
        if (isClassPresent(POSTGRES_DRIVER)) {
            drivers.add("PostgreSQL");
        }
        if (isClassPresent(MONGODB_DRIVER)) {
            drivers.add("MongoDB");
        }

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
                                                    boolean hasMongoConfig,
                                                    String sqlUrl) {
        List<String> missingConfigs = new ArrayList<>();

        for (String driver : availableDrivers) {
            switch (driver) {
                case "MySQL":
                    if (!hasSqlConfig || (sqlUrl != null && !sqlUrl.contains("mysql"))) {
                        missingConfigs.add("MySQL");
                    }
                    break;
                case "PostgreSQL":
                    if (!hasSqlConfig || (sqlUrl != null && !sqlUrl.contains("postgresql"))) {
                        missingConfigs.add("PostgreSQL");
                    }
                    break;
                case "MongoDB":
                    if (!hasMongoConfig) {
                        missingConfigs.add("MongoDB");
                    }
                    break;
            }
        }

        if (!missingConfigs.isEmpty()) {
            String errorMessage = buildMissingConfigErrorMessage(missingConfigs);
            logError(errorMessage);
            throw new IllegalStateException("Database driver(s) detected but configuration missing: " +
                    String.join(", ", missingConfigs));
        }
    }

    private String buildNoConfigErrorMessage(List<String> availableDrivers) {
        StringBuilder message = new StringBuilder();
        message.append("\n");
        message.append(formatBoxTop());
        message.append(formatBoxTitle("DATABASE CONFIGURATION ERROR"));
        message.append(formatBoxSeparator());
        message.append(formatBoxRow("Database driver(s) found in classpath but not configured!"));
        message.append(formatBoxRow(""));
        message.append(formatBoxRow("Detected drivers:", CYAN));

        for (String driver : availableDrivers) {
            message.append(formatBoxRow("  • " + driver, WHITE));
        }

        message.append(formatBoxRow(""));
        message.append(formatBoxRow("Please configure at least one database:", YELLOW));
        message.append(formatBoxRow(""));

        if (availableDrivers.contains("MySQL")) {
            message.append(formatBoxRow("For MySQL:", GREEN));
            message.append(formatBoxRow("  spring.datasource.url=jdbc:mysql://localhost:3306/dbname"));
            message.append(formatBoxRow("  spring.datasource.username=root"));
            message.append(formatBoxRow("  spring.datasource.password=password"));
            message.append(formatBoxRow("  spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver"));
            message.append(formatBoxRow(""));
        }

        if (availableDrivers.contains("PostgreSQL")) {
            message.append(formatBoxRow("For PostgreSQL:", GREEN));
            message.append(formatBoxRow("  spring.datasource.url=jdbc:postgresql://localhost:5432/db"));
            message.append(formatBoxRow("  spring.datasource.username=postgres"));
            message.append(formatBoxRow("  spring.datasource.password=password"));
            message.append(formatBoxRow("  spring.datasource.driver-class-name=org.postgresql.Driver"));
            message.append(formatBoxRow(""));
        }

        if (availableDrivers.contains("MongoDB")) {
            message.append(formatBoxRow("For MongoDB:", GREEN));
            message.append(formatBoxRow("  spring.data.mongodb.uri=mongodb://localhost:27017/dbname"));
            message.append(formatBoxRow(""));
        }

        message.append(formatBoxBottom());
        return message.toString();
    }

    private String buildMissingConfigErrorMessage(List<String> missingConfigs) {
        StringBuilder message = new StringBuilder();
        message.append("\n");
        message.append(formatBoxTop());
        message.append(formatBoxTitle("DATABASE DRIVER-CONFIGURATION MISMATCH"));
        message.append(formatBoxSeparator());
        message.append(formatBoxRow("The following database driver(s) are in your classpath"));
        message.append(formatBoxRow("but corresponding configurations are missing:"));
        message.append(formatBoxRow(""));

        for (String driver : missingConfigs) {
            message.append(formatBoxRow("  ✗ " + driver, RED));
        }

        message.append(formatBoxRow(""));
        message.append(formatBoxRow("Please add configuration for the detected driver(s):", YELLOW));
        message.append(formatBoxRow(""));

        if (missingConfigs.contains("MySQL")) {
            message.append(formatBoxRow("MySQL Configuration Required:", GREEN));
            message.append(formatBoxRow("  spring.datasource.url=jdbc:mysql://localhost:3306/dbname"));
            message.append(formatBoxRow("  spring.datasource.username=root"));
            message.append(formatBoxRow("  spring.datasource.password=password"));
            message.append(formatBoxRow("  spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver"));
            message.append(formatBoxRow(""));
        }

        if (missingConfigs.contains("PostgreSQL")) {
            message.append(formatBoxRow("PostgreSQL Configuration Required:", GREEN));
            message.append(formatBoxRow("  spring.datasource.url=jdbc:postgresql://localhost:5432/db"));
            message.append(formatBoxRow("  spring.datasource.username=postgres"));
            message.append(formatBoxRow("  spring.datasource.password=password"));
            message.append(formatBoxRow("  spring.datasource.driver-class-name=org.postgresql.Driver"));
            message.append(formatBoxRow(""));
        }

        if (missingConfigs.contains("MongoDB")) {
            message.append(formatBoxRow("MongoDB Configuration Required:", GREEN));
            message.append(formatBoxRow("  spring.data.mongodb.uri=mongodb://localhost:27017/dbname"));
            message.append(formatBoxRow(""));
        }

        message.append(formatBoxRow("OR remove the unused dependency from build.gradle/pom.xml", YELLOW));
        message.append(formatBoxBottom());
        return message.toString();
    }

    private String buildManualDatabaseCreationMessage(String dbType, String dbName, String url) {
        StringBuilder message = new StringBuilder();
        message.append("\n");
        message.append(formatBoxTop());
        message.append(formatBoxTitle("DATABASE MANUAL CREATION REQUIRED"));
        message.append(formatBoxSeparator());
        message.append(formatBoxRow("Auto-creation is disabled (crudx.database.auto-create=false)"));
        message.append(formatBoxRow("Database '" + dbName + "' does not exist!", RED));
        message.append(formatBoxRow(""));
        message.append(formatBoxRow("Database Type: " + dbType, CYAN));
        message.append(formatBoxRow("Connection URL: " + maskPassword(url), CYAN));
        message.append(formatBoxRow(""));

        if ("MySQL".equals(dbType)) {
            message.append(formatBoxRow("Please create the database manually using MySQL CLI:", YELLOW));
            message.append(formatBoxRow(""));
            message.append(formatBoxRow("Step 1: Connect to MySQL", GREEN));
            message.append(formatBoxRow("  mysql -u root -p"));
            message.append(formatBoxRow(""));
            message.append(formatBoxRow("Step 2: Create database", GREEN));
            message.append(formatBoxRow("  CREATE DATABASE `" + dbName + "`"));
            message.append(formatBoxRow("         CHARACTER SET utf8mb4"));
            message.append(formatBoxRow("         COLLATE utf8mb4_unicode_ci;"));
            message.append(formatBoxRow(""));
            message.append(formatBoxRow("Step 3: Verify database creation", GREEN));
            message.append(formatBoxRow("  SHOW DATABASES;"));
            message.append(formatBoxRow(""));
            message.append(formatBoxRow("Step 4: Grant permissions (if needed)", GREEN));
            message.append(formatBoxRow("  GRANT ALL PRIVILEGES ON `" + dbName + "`.* TO 'your_user'@'%';"));
            message.append(formatBoxRow("  FLUSH PRIVILEGES;"));
        } else if ("PostgreSQL".equals(dbType)) {
            message.append(formatBoxRow("Please create the database manually using PostgreSQL CLI:", YELLOW));
            message.append(formatBoxRow(""));
            message.append(formatBoxRow("Step 1: Connect to PostgreSQL", GREEN));
            message.append(formatBoxRow("  psql -U postgres"));
            message.append(formatBoxRow(""));
            message.append(formatBoxRow("Step 2: Create database", GREEN));
            message.append(formatBoxRow("  CREATE DATABASE \"" + dbName + "\" WITH ENCODING 'UTF8';"));
            message.append(formatBoxRow(""));
            message.append(formatBoxRow("Step 3: Verify database creation", GREEN));
            message.append(formatBoxRow("  \\l"));
            message.append(formatBoxRow(""));
            message.append(formatBoxRow("Step 4: Grant permissions (if needed)", GREEN));
            message.append(formatBoxRow("  GRANT ALL PRIVILEGES ON DATABASE \"" + dbName + "\" TO your_user;"));
        }

        message.append(formatBoxRow(""));
        message.append(formatBoxRow("Alternative: Enable auto-creation", CYAN));
        message.append(formatBoxRow("  crudx.database.auto-create=true"));
        message.append(formatBoxBottom());
        return message.toString();
    }

    private String buildDriverNotFoundErrorMessage(String dbType, String driverClass, String url) {
        StringBuilder message = new StringBuilder();
        message.append("\n");
        message.append(formatBoxTop());
        message.append(formatBoxTitle("DATABASE DRIVER NOT FOUND ERROR"));
        message.append(formatBoxSeparator());
        message.append(formatBoxRow("Database configuration found but driver is missing!"));
        message.append(formatBoxRow(""));
        message.append(formatBoxRow("Database Type: " + dbType, CYAN));
        message.append(formatBoxRow("Driver Class: " + driverClass, CYAN));
        message.append(formatBoxRow("Connection URL: " + maskPassword(url), CYAN));
        message.append(formatBoxRow(""));
        message.append(formatBoxRow("You have TWO options:", YELLOW));
        message.append(formatBoxRow(""));
        message.append(formatBoxRow("OPTION 1: Add the missing database driver dependency", GREEN));
        message.append(formatBoxRow(""));

        if ("MySQL".equals(dbType)) {
            message.append(formatBoxRow("For Gradle (build.gradle):"));
            message.append(formatBoxRow("  runtimeOnly 'com.mysql:mysql-connector-j:8.3.0'"));
            message.append(formatBoxRow(""));
            message.append(formatBoxRow("For Maven (pom.xml):"));
            message.append(formatBoxRow("  <dependency>"));
            message.append(formatBoxRow("    <groupId>com.mysql</groupId>"));
            message.append(formatBoxRow("    <artifactId>mysql-connector-j</artifactId>"));
            message.append(formatBoxRow("    <version>8.3.0</version>"));
            message.append(formatBoxRow("    <scope>runtime</scope>"));
            message.append(formatBoxRow("  </dependency>"));
        } else if ("PostgreSQL".equals(dbType)) {
            message.append(formatBoxRow("For Gradle (build.gradle):"));
            message.append(formatBoxRow("  runtimeOnly 'org.postgresql:postgresql:42.7.3'"));
            message.append(formatBoxRow(""));
            message.append(formatBoxRow("For Maven (pom.xml):"));
            message.append(formatBoxRow("  <dependency>"));
            message.append(formatBoxRow("    <groupId>org.postgresql</groupId>"));
            message.append(formatBoxRow("    <artifactId>postgresql</artifactId>"));
            message.append(formatBoxRow("    <version>42.7.3</version>"));
            message.append(formatBoxRow("    <scope>runtime</scope>"));
            message.append(formatBoxRow("  </dependency>"));
        }

        message.append(formatBoxRow(""));
        message.append(formatBoxRow("OPTION 2: Remove the database configuration", GREEN));
        message.append(formatBoxRow(""));
        message.append(formatBoxRow("Remove or comment out these properties from your"));
        message.append(formatBoxRow("application.yml or application.properties:"));
        message.append(formatBoxRow(""));
        message.append(formatBoxRow("  spring.datasource.url"));
        message.append(formatBoxRow("  spring.datasource.username"));
        message.append(formatBoxRow("  spring.datasource.password"));
        message.append(formatBoxRow("  spring.datasource.driver-class-name"));
        message.append(formatBoxRow(""));
        message.append(formatBoxRow("After removing the configuration, the application will use"));
        message.append(formatBoxRow("only the available database(s) (e.g., MongoDB)"));
        message.append(formatBoxBottom());
        return message.toString();
    }

    private String buildConnectionErrorMessage(String url, String error) {
        StringBuilder message = new StringBuilder();
        message.append("\n");
        message.append(formatBoxTop());
        message.append(formatBoxTitle("SQL DATABASE CONNECTION ERROR"));
        message.append(formatBoxSeparator());
        message.append(formatBoxRow("Failed to connect to SQL database server", RED));
        message.append(formatBoxRow(""));
        message.append(formatBoxRow("Error: " + truncate(error, 60), RED));
        message.append(formatBoxRow(""));
        message.append(formatBoxRow("Please verify:", YELLOW));
        message.append(formatBoxRow("  ✗ Database server is running"));
        message.append(formatBoxRow("  ✗ Connection URL is correct"));
        message.append(formatBoxRow("  ✗ Username and password are valid"));
        message.append(formatBoxRow("  ✗ Network connectivity to database server"));
        message.append(formatBoxRow(""));
        message.append(formatBoxRow("Configuration:", CYAN));
        message.append(formatBoxRow("  URL: " + maskPassword(url)));
        message.append(formatBoxBottom());
        return message.toString();
    }

    private void logActiveConfigurations(boolean hasSqlConfig, boolean hasMongoConfig, String sqlUrl, String mongoUri) {
        log.info(CYAN + "╔════════════════════════════════════════════════════════════════════╗" + RESET);
        log.info(CYAN + "║" + BOLD + WHITE + "              Active Database Configurations                    " + RESET + CYAN + "║" + RESET);
        log.info(CYAN + "╠════════════════════════════════════════════════════════════════════╣" + RESET);

        if (hasSqlConfig) {
            String dbType = detectSqlDatabaseType(sqlUrl);
            log.info(CYAN + "║" + RESET + "  " + GREEN + "✓" + RESET + " SQL Database (" + BOLD + dbType + RESET + ")" +
                    " ".repeat(Math.max(0, 47 - dbType.length())) + CYAN + "║" + RESET);
            String maskedUrl = maskPassword(sqlUrl);
            log.info(CYAN + "║" + RESET + "    URL: " + truncate(maskedUrl, 58) +
                    " ".repeat(Math.max(0, 58 - truncate(maskedUrl, 58).length())) + CYAN + "║" + RESET);
        }

        if (hasMongoConfig) {
            log.info(CYAN + "║" + RESET + "  " + GREEN + "✓" + RESET + " MongoDB Database" +
                    " ".repeat(48) + CYAN + "║" + RESET);
            String maskedUri = maskPassword(mongoUri);
            log.info(CYAN + "║" + RESET + "    URI: " + truncate(maskedUri, 57) +
                    " ".repeat(Math.max(0, 57 - truncate(maskedUri, 57).length())) + CYAN + "║" + RESET);
        }

        log.info(CYAN + "╚════════════════════════════════════════════════════════════════════╝" + RESET);
    }

    private void handleSqlDatabaseInitialization(Environment env, String url) {
        Boolean autoCreate = env.getProperty("crudx.database.auto-create", Boolean.class, true);

        String databaseName = extractDatabaseName(url);
        if (databaseName == null) {
            logWarning("Could not extract database name from URL: " + maskPassword(url));
            return;
        }

        if (!autoCreate) {
            log.info(YELLOW + "Database auto-creation is disabled (crudx.database.auto-create=false)" + RESET);
            try {
                validateSqlConnection(env, url);
            } catch (Exception e) {
                String dbType = detectSqlDatabaseType(url);
                String manualInstructions = buildManualDatabaseCreationMessage(dbType, databaseName, url);
                logError(manualInstructions);
                throw new IllegalStateException("Database '" + databaseName + "' does not exist. Please create it manually or enable auto-creation.", e);
            }
            return;
        }

        createDatabaseIfNotExists(env, url, databaseName);
    }

    private void validateSqlConnection(Environment env, String url) {
        String username = env.getProperty("spring.datasource.username");
        String password = env.getProperty("spring.datasource.password");
        String driver = env.getProperty("spring.datasource.driver-class-name");

        if (driver == null) {
            throw new IllegalStateException("spring.datasource.driver-class-name is required but not configured");
        }

        try {
            Class.forName(driver);
            try (Connection conn = DriverManager.getConnection(url, username, password)) {
                log.info(GREEN + "✓ SQL database connection validated successfully" + RESET);
            }
        } catch (ClassNotFoundException e) {
            String dbType = detectSqlDatabaseType(url);
            String errorMessage = buildDriverNotFoundErrorMessage(dbType, driver, url);
            logError(errorMessage);
            throw new IllegalStateException("Database driver not found: " + driver, e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to connect to SQL database", e);
        }
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
            log.error("Error extracting database name from URL", e);
        }
        return null;
    }

    private void createDatabaseIfNotExists(Environment env, String originalUrl, String dbName) {
        String username = env.getProperty("spring.datasource.username");
        String password = env.getProperty("spring.datasource.password");
        String driver = env.getProperty("spring.datasource.driver-class-name");

        if (driver == null) {
            throw new IllegalStateException("spring.datasource.driver-class-name is required but not configured");
        }

        try {
            Class.forName(driver);
            log.info(CYAN + "Checking/creating database '{}'..." + RESET, dbName);

            if (driver.contains("mysql")) {
                createMySQLDatabaseIfNeeded(originalUrl, username, password, dbName);
            } else if (driver.contains("postgresql")) {
                createPostgreSQLDatabaseIfNeeded(originalUrl, username, password, dbName);
            } else {
                logWarning("Unsupported database driver for auto-creation: " + driver);
                validateConnectionWithDatabase(originalUrl, username, password);
            }
        } catch (ClassNotFoundException e) {
            String dbType = detectSqlDatabaseType(originalUrl);
            String errorMessage = buildDriverNotFoundErrorMessage(dbType, driver, originalUrl);
            logError(errorMessage);
            throw new IllegalStateException("Database driver not found: " + driver, e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to connect to database server", e);
        }
    }

    private void createMySQLDatabaseIfNeeded(String originalUrl, String username, String password, String dbName) throws Exception {
        String baseUrl = originalUrl.substring(0, originalUrl.lastIndexOf("/"));

        try (Connection conn = DriverManager.getConnection(baseUrl, username, password);
             Statement stmt = conn.createStatement()) {

            String createDbSql = String.format(
                    "CREATE DATABASE IF NOT EXISTS `%s` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci",
                    dbName
            );
            stmt.executeUpdate(createDbSql);
            log.info(GREEN + "✓ MySQL database '" + dbName + "' created/verified successfully" + RESET);
        }
    }

    private void createPostgreSQLDatabaseIfNeeded(String originalUrl, String username, String password, String dbName) throws Exception {
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
                            String createDbSql = String.format("CREATE DATABASE \"%s\" WITH ENCODING 'UTF8'",
                                    dbName.replace("\"", "\"\""));
                            createStmt.executeUpdate(createDbSql);
                            log.info(GREEN + "✓ PostgreSQL database '" + dbName + "' created successfully" + RESET);
                        }
                    } else {
                        log.info(GREEN + "✓ PostgreSQL database '" + dbName + "' already exists" + RESET);
                    }
                }
            }
        }
    }

    private void validateConnectionWithDatabase(String url, String username, String password) throws Exception {
        try (Connection conn = DriverManager.getConnection(url, username, password)) {
            log.info(GREEN + "✓ Database connection validated successfully" + RESET);
        }
    }

    private void validateMongoConnection(String mongoUri) {
        log.info(CYAN + "MongoDB configuration detected - connection will be validated on first use" + RESET);
    }

    private String detectSqlDatabaseType(String url) {
        if (url.contains("mysql")) {
            return "MySQL";
        } else if (url.contains("postgresql")) {
            return "PostgreSQL";
        } else if (url.contains("oracle")) {
            return "Oracle";
        } else if (url.contains("sqlserver")) {
            return "SQL Server";
        }
        return "Unknown";
    }

    private String maskPassword(String url) {
        if (url == null) {
            return "null";
        }
        return url.replaceAll(":[^:@]+@", ":****@");
    }

    private String truncate(String str, int maxLength) {
        if (str == null) {
            return "";
        }
        if (str.length() <= maxLength) {
            return str;
        }
        return str.substring(0, maxLength - 3) + "...";
    }

    // Box formatting helpers
    private String formatBoxTop() {
        return CYAN + "╔════════════════════════════════════════════════════════════════════╗\n" + RESET;
    }

    private String formatBoxBottom() {
        return CYAN + "╚════════════════════════════════════════════════════════════════════╝\n" + RESET;
    }

    private String formatBoxSeparator() {
        return CYAN + "╠════════════════════════════════════════════════════════════════════╣\n" + RESET;
    }

    private String formatBoxTitle(String title) {
        int totalWidth = 68;
        int padding = (totalWidth - title.length()) / 2;
        String leftPad = " ".repeat(padding);
        String rightPad = " ".repeat(totalWidth - title.length() - padding);
        return CYAN + "║" + RESET + leftPad + BOLD + WHITE + title + RESET + rightPad + CYAN + "║\n" + RESET;
    }

    private String formatBoxRow(String content) {
        return formatBoxRow(content, WHITE);
    }

    private String formatBoxRow(String content, String color) {
        int totalWidth = 68;
        String strippedContent = content.replaceAll("\u001B\\[[;\\d]*m", "");
        int contentLength = strippedContent.length();
        int padding = totalWidth - contentLength;
        return CYAN + "║" + RESET + " " + color + content + RESET + " ".repeat(Math.max(0, padding - 1)) + CYAN + "║\n" + RESET;
    }

    private void logError(String message) {
        System.err.println(message);
    }

    private void logWarning(String message) {
        log.warn(YELLOW + message + RESET);
    }
}