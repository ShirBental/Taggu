package com.taggu.app.api;

import com.taggu.app.importer.ImportService.ImportFailedException;
import com.taggu.app.importer.ImportService.ImportRejectedException;
import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

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

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ApiError> handleBadRequest(BadRequestException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiError.of(HttpStatus.BAD_REQUEST, ex.getMessage()));
    }

    /** A value that failed a declared constraint, such as a blank search query. */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraintViolation(ConstraintViolationException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of(HttpStatus.BAD_REQUEST, "the request could not be understood"));
    }

    /** A parameter of the wrong shape: a conversation id that is not a UUID, a limit that is not a number. */
    @ExceptionHandler(TypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(TypeMismatchException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of(HttpStatus.BAD_REQUEST, "the request could not be understood"));
    }

    @ExceptionHandler(ImportRejectedException.class)
    public ResponseEntity<ApiError> handleImportRejected(ImportRejectedException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ApiError.of(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleTooLarge(MaxUploadSizeExceededException ex) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ApiError.of(HttpStatus.PAYLOAD_TOO_LARGE, "the uploaded file is too large"));
    }

    @ExceptionHandler(ImportFailedException.class)
    public ResponseEntity<ApiError> handleImportFailed(ImportFailedException ex) {
        log.error("Import failed", ex);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of(HttpStatus.BAD_REQUEST, "the export could not be read"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
        // Spring's own verdicts on a request - a missing parameter, a malformed id, an unknown path,
        // the wrong method - are the caller's mistakes and say so with a 4xx status. They used to fall
        // through to the 500 below.
        if (ex instanceof ErrorResponse response) {
            HttpStatus status = HttpStatus.resolve(response.getStatusCode().value());
            if (status != null && status.is4xxClientError()) {
                return ResponseEntity.status(status).body(ApiError.of(status, "the request could not be understood"));
            }
        }
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
