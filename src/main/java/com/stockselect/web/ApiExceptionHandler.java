package com.stockselect.web;

import com.stockselect.UpstreamApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(new ErrorResponse(ex.getMessage()));
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NoHandlerFoundException ex) {
        String message = "No such endpoint: " + ex.getHttpMethod() + " " + ex.getRequestURL();
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(message));
    }

    @ExceptionHandler(UpstreamApiException.class)
    public ResponseEntity<ErrorResponse> handleUpstreamError(UpstreamApiException ex) {
        if (ex.status().value() == 429) {
            String message = "Upstream data provider rate limit exceeded — try again later.";
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(new ErrorResponse(message));
        }
        if (ex.status().value() == 401 || ex.status().value() == 403) {
            String message = "Upstream data provider rejected the request — check the API key and plan entitlements.";
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new ErrorResponse(message));
        }
        String message = "Upstream data provider request failed (" + ex.status().value() + ").";
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new ErrorResponse(message));
    }

    /**
     * Catch-all for anything not handled above. Logs the full exception server-side but returns
     * a generic message — the exception's own message could leak internal details (file paths,
     * SQL, stack info) that shouldn't go to an API caller.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpectedError(Exception ex) {
        log.error("Unexpected error handling request", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("An unexpected error occurred."));
    }
}
