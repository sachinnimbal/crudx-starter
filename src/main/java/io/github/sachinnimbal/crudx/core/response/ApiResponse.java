package io.github.sachinnimbal.crudx.core.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;


/**
 * @author Sachin Nimbal
 * @version 1.0.0-SNAPSHOT
 * @since 2025
 * @Contact: <a href="mailto:sachinnimbal9@gmail.com">sachinnimbal9@gmail.com</a>
 * @see <a href="https://www.linkedin.com/in/sachin-nimbal/">LinkedIn Profile</a>
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {
    private boolean success;
    private String message;
    private Integer statusCode;
    private String status;
    private T data;
    private ErrorDetails error;
    private String timestamp;
    private String executionTime; // New field for execution time

    public static <T> ApiResponse<T> success(T data, String message, HttpStatus status) {
        return ApiResponse.<T>builder()
                .success(true)
                .message(message)
                .statusCode(status.value())
                .status(status.name())
                .data(data)
                .timestamp(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                .build();
    }

    // New method with execution time
    public static <T> ApiResponse<T> success(T data, String message, HttpStatus status, long executionTimeMs) {
        return ApiResponse.<T>builder()
                .success(true)
                .message(message)
                .statusCode(status.value())
                .status(status.name())
                .data(data)
                .executionTime(formatExecutionTime(executionTimeMs))
                .timestamp(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                .build();
    }

    public static <T> ApiResponse<T> success(T data, String message, long executionTimeMs) {
        return success(data, message, HttpStatus.OK, executionTimeMs);
    }

    public static <T> ApiResponse<T> success(T data, String message) {
        return success(data, message, HttpStatus.OK);
    }

    public static <T> ApiResponse<T> success(T data) {
        return success(data, "Operation successful");
    }

    public static <T> ApiResponse<T> error(String message, HttpStatus status, String errorCode, String details) {
        return ApiResponse.<T>builder()
                .success(false)
                .message(message)
                .statusCode(status.value())
                .status(status.name())
                .error(ErrorDetails.builder()
                        .code(errorCode)
                        .details(details)
                        .build())
                .timestamp(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                .build();
    }

    public static <T> ApiResponse<T> error(String message, HttpStatus status, long executionTimeMs) {
        return ApiResponse.<T>builder()
                .success(false)
                .message(message)
                .statusCode(status.value())
                .status(status.name())
                .executionTime(formatExecutionTime(executionTimeMs))
                .timestamp(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                .build();
    }

    public static <T> ApiResponse<T> error(String message, HttpStatus status) {
        return error(message, status, status.name(), message);
    }

    public static <T> ApiResponse<T> notFound(String message) {
        return ApiResponse.<T>builder()
                .success(false)
                .message(message)
                .statusCode(HttpStatus.NOT_FOUND.value())
                .status(HttpStatus.NOT_FOUND.name())
                .timestamp(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                .build();
    }

    private static String formatExecutionTime(long milliseconds) {
        if (milliseconds < 1000) {
            // Less than 1 second
            return milliseconds + " ms";
        } else if (milliseconds < 60000) {
            // Less than 1 minute
            double seconds = milliseconds / 1000.0;
            return String.format("%.2fs (%d ms)", seconds, milliseconds);
        } else if (milliseconds < 3600000) {
            // Less than 1 hour
            long minutes = milliseconds / 60000;
            long seconds = (milliseconds % 60000) / 1000;
            return String.format("%dm %ds (%d ms)", minutes, seconds, milliseconds);
        } else {
            // 1 hour or more
            long hours = milliseconds / 3600000;
            long minutes = (milliseconds % 3600000) / 60000;
            long seconds = (milliseconds % 60000) / 1000;
            return String.format("%dh %dm %ds (%d ms)", hours, minutes, seconds, milliseconds);
        }
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ErrorDetails {
        private String code;
        private String details;
    }
}