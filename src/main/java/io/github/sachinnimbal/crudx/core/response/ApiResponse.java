package io.github.sachinnimbal.crudx.core.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static io.github.sachinnimbal.crudx.core.util.TimeUtils.formatExecutionTime;

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
    private String executionTime;

    // Batch operation metadata
    private BatchMetadata batchMetadata;
    private List<String> warnings;

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

    // Batch success with metadata
    public static <T> ApiResponse<T> batchSuccess(T data, String message, HttpStatus status,
                                                  long executionTimeMs, int successCount,
                                                  int skippedCount, int duplicateCount) {
        BatchMetadata metadata = new BatchMetadata();
        metadata.successCount = successCount;
        metadata.skippedCount = skippedCount;
        metadata.duplicateCount = duplicateCount;
        metadata.totalProcessed = successCount + skippedCount;
        metadata.successRate = metadata.totalProcessed > 0
                ? (successCount * 100.0) / metadata.totalProcessed : 0.0;

        return ApiResponse.<T>builder()
                .success(skippedCount < metadata.totalProcessed) // Partial success if any skipped
                .message(message)
                .statusCode(status.value())
                .status(status.name())
                .data(data)
                .batchMetadata(metadata)
                .executionTime(formatExecutionTime(executionTimeMs))
                .timestamp(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                .build();
    }

    // Partial success (for batch operations with some failures)
    public static <T> ApiResponse<T> partialSuccess(T data, String message,
                                                    int successCount, int skippedCount,
                                                    List<String> warnings, long executionTimeMs) {
        BatchMetadata metadata = new BatchMetadata();
        metadata.successCount = successCount;
        metadata.skippedCount = skippedCount;
        metadata.totalProcessed = successCount + skippedCount;
        metadata.successRate = metadata.totalProcessed > 0
                ? (successCount * 100.0) / metadata.totalProcessed : 0.0;

        return ApiResponse.<T>builder()
                .success(true) // Still success if at least some records processed
                .message(message)
                .statusCode(HttpStatus.PARTIAL_CONTENT.value())
                .status(HttpStatus.PARTIAL_CONTENT.name())
                .data(data)
                .batchMetadata(metadata)
                .warnings(warnings != null && warnings.size() > 10
                        ? warnings.subList(0, 10) : warnings)
                .executionTime(formatExecutionTime(executionTimeMs))
                .timestamp(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                .build();
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

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ErrorDetails {
        private String code;
        private String details;
    }

    // Batch operation metadata
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class BatchMetadata {
        private Integer successCount;
        private Integer skippedCount;
        private Integer duplicateCount;
        private Integer validationFailCount;
        private Integer totalProcessed;
        private Double successRate;
    }
}