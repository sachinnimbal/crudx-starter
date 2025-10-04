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

    // ANSI escape codes
    private static final String RESET = "\u001B[0m";
    // Define two sets of colors (light vs dark friendly)
    private static final String DARK_MODE_TEXT = "\u001B[37m";   // White
    private static final String LIGHT_MODE_TEXT = "\u001B[30m";  // Black (dark text)
    // Fallback
    private static final String DEFAULT_TEXT = "\u001B[37m";
    private static final String RED = "\u001B[31m";
    private static final String GREEN = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String CYAN = "\u001B[36m";
    private static final String BOLD = "\u001B[1m";

    private static final long STARTUP_TIME = System.currentTimeMillis();

    public CrudXBannerConfiguration(Environment environment) {
        this.environment = environment;
    }

    // Decide based on property
    private String getThemeColor() {
        String theme = environment.getProperty("samvya.theme", "dark").toLowerCase();
        return switch (theme) {
            case "light" -> LIGHT_MODE_TEXT;
            case "dark" -> DARK_MODE_TEXT;
            default -> DEFAULT_TEXT;
        };
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        printBannerWithEffect();
        simulateProgressBar();
        printDatabaseInfo();
        printStartupTime();
    }

    private void printBannerWithEffect() {
        String textColor = getThemeColor();
        String[] bannerLines = {
                BOLD + textColor,
                "  █████████                                                       ",
                " ███░░░░░███                                                      ",
                "░███    ░░░  ██████  █████████████  █████ ██████████ ████ ██████  ",
                "░░█████████ ░░░░░███░░███░░███░░███░░███ ░░███░░███ ░███ ░░░░░███ ",
                " ░░░░░░░░███ ███████ ░███ ░███ ░███ ░███  ░███ ░███ ░███  ███████ ",
                " ███    ░██████░░███ ░███ ░███ ░███ ░░███ ███  ░███ ░███ ███░░███ ",
                "░░█████████░░█████████████░███ █████ ░░█████   ░░███████░░████████",
                " ░░░░░░░░░  ░░░░░░░░░░░░░ ░░░ ░░░░░   ░░░░░     ░░░░░███ ░░░░░░░░ ",
                "                                                ███ ░███          ",
                "                                               ░░██████           ",
                "                                                ░░░░░░            ",
                "        CRUD Framework - Version 1.0.0               ",
                "      Lightweight & High-Performance CRUD            ",
                RESET
        };

        for (String line : bannerLines) {
            System.out.println(line);
            try {
                Thread.sleep(60); // keep the slide-up effect
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void simulateProgressBar() {
        System.out.print(CYAN + "Starting Application: " + RESET);
        int total = 30;
        for (int i = 0; i < total; i++) {
            System.out.print(GREEN + "█" + RESET);
            try {
                Thread.sleep(50); // adjust speed here
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        System.out.println(" " + GREEN + "DONE!" + RESET + " 🚀");
    }

    private void printDatabaseInfo() {
        System.out.println(CYAN + "╔════════════════════════════════════════════════════════════════════════╗" + RESET);
        System.out.println(CYAN + "║                    " + BOLD + "Database Configuration Status" + RESET + CYAN + "                       ║" + RESET);
        System.out.println(CYAN + "╠════════════════════════════════════════════════════════════════════════╣" + RESET);

        boolean mongoEnabled = isMongoEnabled();
        boolean mysqlEnabled = isMySqlEnabled();
        boolean postgresEnabled = isPostgresEnabled();

        // Helper function for proper spacing
        System.out.println(formatCapabilityRow("MongoDB Support", mongoEnabled));
        System.out.println(formatCapabilityRow("MySQL Support", mysqlEnabled));
        System.out.println(formatCapabilityRow("PostgreSQL Support", postgresEnabled));

        System.out.println(CYAN + "╚════════════════════════════════════════════════════════════════════════╝" + RESET);
        System.out.println(YELLOW + "📌 Note: Configure only one database provider." + RESET);
        System.out.println(YELLOW + "   ▶ Use either MongoDB OR MySQL/PostgreSQL." + RESET);
        System.out.println(YELLOW + "   ▶ MySQL and PostgreSQL cannot be active at the same time." + RESET);
        System.out.println();
        System.out.println("🚀 " + GREEN + BOLD + "Samvya CRUD Framework is ready!" + RESET);
        System.out.println("📚 " + CYAN + "Documentation: " + YELLOW + "https://github.com/sachinnimbal/samvya-crud-examples/wiki" + RESET);
        System.out.println();
    }

    private String formatCapabilityRow(String name, boolean isActive) {
        String status = isActive ? GREEN + "✓ Configured" : RED + "✗ Not Configured";
        int totalWidth = 73; // total width inside borders

        // row without padding
        String row = "║ ▶ " + String.format("%-18s", name) + ": " + status;

        // remove ANSI codes to calculate padding
        int visibleLength = row.replaceAll("\u001B\\[[;\\d]*m", "").length();
        int remaining = totalWidth - visibleLength;

        // pad spaces, then add right border in CYAN
        return row + " ".repeat(Math.max(0, remaining)) + CYAN + "║" + RESET;
    }

    private void printStartupTime() {
        long end = System.currentTimeMillis();
        double durationSec = (end - STARTUP_TIME) / 1000.0;
        System.out.printf(YELLOW + BOLD + "⏱ Application started in %.3f seconds%n" + RESET, durationSec);
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
