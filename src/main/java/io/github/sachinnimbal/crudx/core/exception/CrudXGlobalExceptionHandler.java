package io.github.sachinnimbal.crudx.core.exception;

import io.github.sachinnimbal.crudx.core.response.ApiResponse;
import io.github.sachinnimbal.crudx.web.CrudXController;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;


/**
 * @author Sachin Nimbal
 * @version 1.0.0
 * @since 2025
 * @Contact: <a href="mailto:sachinnimbal9@gmail.com">sachinnimbal9@gmail.com</a>
 * @see <a href="https://www.linkedin.com/in/sachin-nimbal/">LinkedIn Profile</a>
 */
@Slf4j
@RestControllerAdvice(assignableTypes = CrudXController.class)
public class CrudXGlobalExceptionHandler {

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleEntityNotFound(
            EntityNotFoundException ex, WebRequest request) {

        log.error("Entity not found: {}", ex.getMessage());

        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(
                        ex.getMessage(),
                        HttpStatus.NOT_FOUND,
                        "ENTITY_NOT_FOUND",
                        ex.getMessage()
                ));
    }

    @ExceptionHandler(DuplicateEntityException.class)
    public ResponseEntity<ApiResponse<Void>> handleDuplicateEntity(
            DuplicateEntityException ex, WebRequest request) {

        log.error("Duplicate entity: {}", ex.getMessage());

        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(
                        ex.getMessage(),
                        HttpStatus.CONFLICT,
                        "DUPLICATE_ENTITY",
                        ex.getMessage()
                ));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleValidationExceptions(
            MethodArgumentNotValidException ex) {

        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach(error -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });

        log.error("Validation failed: {}", errors);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.<Map<String, String>>builder()
                        .success(false)
                        .message("Validation failed")
                        .statusCode(HttpStatus.BAD_REQUEST.value())
                        .status(HttpStatus.BAD_REQUEST.name())
                        .data(errors)
                        .error(new ApiResponse.ErrorDetails("VALIDATION_ERROR", "Field validation failed"))
                        .build());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(
            IllegalArgumentException ex, WebRequest request) {

        log.error("Illegal argument: {}", ex.getMessage());

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(
                        ex.getMessage(),
                        HttpStatus.BAD_REQUEST,
                        "INVALID_ARGUMENT",
                        ex.getMessage()
                ));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataIntegrityViolation(
            DataIntegrityViolationException ex, WebRequest request) {

        log.error("Data integrity violation: {}", ex.getMessage());

        String message = "Database constraint violation";
        String details = ex.getMostSpecificCause().getMessage();

        if (details != null) {
            if (details.contains("unique") || details.contains("duplicate")) {
                message = "A record with this information already exists";
            } else if (details.contains("foreign key")) {
                message = "Cannot perform operation due to related data constraints";
            } else if (details.contains("null")) {
                message = "Required field cannot be null";
            }
        }

        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(
                        message,
                        HttpStatus.CONFLICT,
                        "DATA_INTEGRITY_VIOLATION",
                        details
                ));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataAccessException(
            DataAccessException ex, WebRequest request) {

        log.error("Data access error: {}", ex.getMessage(), ex);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(
                        "Database operation failed",
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "DATABASE_ERROR",
                        ex.getMostSpecificCause().getMessage()
                ));
    }

    @ExceptionHandler(SQLException.class)
    public ResponseEntity<ApiResponse<Void>> handleSQLException(
            SQLException ex, WebRequest request) {

        log.error("SQL error: {}", ex.getMessage(), ex);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(
                        "Database query failed",
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "SQL_ERROR",
                        ex.getMessage()
                ));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, WebRequest request) {

        log.error("Message not readable: {}", ex.getMessage());

        String message = "Invalid request body format";
        String details = ex.getMostSpecificCause().getMessage();

        if (details != null) {
            if (details.contains("JSON")) {
                message = "Invalid JSON format in request body";
            } else if (details.contains("type")) {
                message = "Invalid data type in request body";
            }
        }

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(
                        message,
                        HttpStatus.BAD_REQUEST,
                        "INVALID_REQUEST_BODY",
                        details
                ));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodArgumentTypeMismatch(
            MethodArgumentTypeMismatchException ex, WebRequest request) {

        log.error("Type mismatch: {}", ex.getMessage());

        String message = String.format("Invalid value '%s' for parameter '%s'. Expected type: %s",
                ex.getValue(),
                ex.getName(),
                ex.getRequiredType() != null ? ex.getRequiredType().getSimpleName() : "unknown");

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(
                        message,
                        HttpStatus.BAD_REQUEST,
                        "TYPE_MISMATCH",
                        ex.getMessage()
                ));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingServletRequestParameter(
            MissingServletRequestParameterException ex, WebRequest request) {

        log.error("Missing parameter: {}", ex.getMessage());

        String message = String.format("Required parameter '%s' of type '%s' is missing",
                ex.getParameterName(),
                ex.getParameterType());

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(
                        message,
                        HttpStatus.BAD_REQUEST,
                        "MISSING_PARAMETER",
                        ex.getMessage()
                ));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleHttpRequestMethodNotSupported(
            HttpRequestMethodNotSupportedException ex, WebRequest request) {

        log.error("Method not supported: {}", ex.getMessage());

        String message = String.format("HTTP method '%s' is not supported for this endpoint. Supported methods: %s",
                ex.getMethod(),
                ex.getSupportedHttpMethods());

        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ApiResponse.error(
                        message,
                        HttpStatus.METHOD_NOT_ALLOWED,
                        "METHOD_NOT_ALLOWED",
                        ex.getMessage()
                ));
    }

    @ExceptionHandler(NullPointerException.class)
    public ResponseEntity<ApiResponse<Void>> handleNullPointerException(
            NullPointerException ex, WebRequest request) {

        log.error("Null pointer exception", ex);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(
                        "An unexpected null value was encountered",
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "NULL_POINTER_ERROR",
                        ex.getMessage() != null ? ex.getMessage() : "Null pointer exception occurred"
                ));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ApiResponse<Void>> handleRuntimeException(
            RuntimeException ex, WebRequest request) {

        log.error("Runtime exception: {}", ex.getMessage(), ex);

        // Check if it's a wrapped exception with a more specific message
        String message = ex.getMessage() != null ? ex.getMessage() : "An unexpected error occurred";

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(
                        message,
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "RUNTIME_ERROR",
                        ex.getCause() != null ? ex.getCause().getMessage() : message
                ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGlobalException(
            Exception ex, WebRequest request) {

        log.error("Unexpected error occurred", ex);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(
                        "An unexpected error occurred. Please contact support if the problem persists",
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "INTERNAL_SERVER_ERROR",
                        ex.getMessage() != null ? ex.getMessage() : "Unknown error"
                ));
    }
}