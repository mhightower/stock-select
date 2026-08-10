package com.stockselect.web;

import com.stockselect.UpstreamApiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(new ErrorResponse(ex.getMessage()));
    }

    @ExceptionHandler(UpstreamApiException.class)
    public ResponseEntity<ErrorResponse> handleUpstreamError(UpstreamApiException ex) {
        if (ex.status().value() == 429) {
            String message = ex.vendor() + " rate limit exceeded — try again later.";
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(new ErrorResponse(message));
        }
        if (ex.status().value() == 401 || ex.status().value() == 403) {
            String message = ex.vendor() + " rejected the request — check the API key and plan entitlements.";
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new ErrorResponse(message));
        }
        String message = ex.vendor() + " request failed (" + ex.status().value() + ").";
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new ErrorResponse(message));
    }
}
