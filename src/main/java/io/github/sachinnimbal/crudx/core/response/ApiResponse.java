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

package io.github.sachinnimbal.crudx.core.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import static io.github.sachinnimbal.crudx.core.util.TimeUtils.formatExecutionTime;

/**
 * @author Sachin Nimbal
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
