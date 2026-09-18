package com.taggu.app.api;

import com.taggu.app.importer.ImportService.ImportFailedException;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns failures into responses without leaking conversation content.
 *
 * <p>An exception raised while parsing or storing a message can easily carry a fragment of what
 * someone wrote. Unexpected failures are therefore reported with a fixed sentence, and the detail
 * stays in the local stack trace at ERROR level rather than travelling in an HTTP body.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(NotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError.of(HttpStatus.NOT_FOUND, ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of(HttpStatus.BAD_REQUEST, "the request could not be understood"));
    }

    @ExceptionHandler(ImportFailedException.class)
    public ResponseEntity<ApiError> handleImportFailed(ImportFailedException ex) {
        log.error("Import failed", ex);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of(HttpStatus.BAD_REQUEST, "the export could not be read"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
        log.error("Unexpected failure handling a request", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of(HttpStatus.INTERNAL_SERVER_ERROR, "the request could not be completed"));
    }

    /** A deliberately content-free error body. */
    public record ApiError(int status, String error, String message, Instant timestamp) {
        static ApiError of(HttpStatus status, String message) {
            return new ApiError(status.value(), status.getReasonPhrase(), message, Instant.now());
        }
    }
}
