package io.peek.core.presentation;

import io.peek.core.events.UnsupportedEventTypeException;
import io.peek.core.integrations.UnsupportedMockPayloadException;
import io.peek.core.shared.ConflictException;
import io.peek.core.shared.NotFoundException;
import java.time.Instant;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiErrors {
    public record ApiError(String code, String message, Instant timestamp) {}

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiError> invalid(IllegalArgumentException error) { return response(HttpStatus.BAD_REQUEST, "INVALID_INPUT", error.getMessage()); }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> malformed(HttpMessageNotReadableException error) { return response(HttpStatus.BAD_REQUEST, "MALFORMED_JSON", "Request body is malformed or has an invalid value"); }
    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ApiError> header(MissingRequestHeaderException error) { return response(HttpStatus.BAD_REQUEST, "MISSING_HEADER", error.getHeaderName() + " is required"); }
    @ExceptionHandler(UnsupportedEventTypeException.class)
    ResponseEntity<ApiError> unsupported(UnsupportedEventTypeException error) { return response(HttpStatus.UNPROCESSABLE_ENTITY, "UNSUPPORTED_EVENT_TYPE", error.getMessage()); }
    @ExceptionHandler(UnsupportedMockPayloadException.class)
    ResponseEntity<ApiError> unsupportedMock(UnsupportedMockPayloadException error) { return response(HttpStatus.UNPROCESSABLE_ENTITY, "UNSUPPORTED_EXTERNAL_PAYLOAD", error.getMessage()); }
    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<ApiError> missing(NotFoundException error) { return response(HttpStatus.NOT_FOUND, "NOT_FOUND", error.getMessage()); }
    @ExceptionHandler(ConflictException.class)
    ResponseEntity<ApiError> conflict(ConflictException error) { return response(HttpStatus.CONFLICT, "CONFLICT", error.getMessage()); }
    @ExceptionHandler({DataIntegrityViolationException.class, ObjectOptimisticLockingFailureException.class})
    ResponseEntity<ApiError> storageConflict(Exception error) { return response(HttpStatus.CONFLICT, "CONFLICT", "Resource conflicts with existing state"); }
    private static ResponseEntity<ApiError> response(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ApiError(code, message, Instant.now()));
    }
}
