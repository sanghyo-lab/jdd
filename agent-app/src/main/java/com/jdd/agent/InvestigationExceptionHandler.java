package com.jdd.agent;

import com.jdd.agent.domain.Investigation.ApiError;
import com.jdd.agent.domain.InvestigationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class InvestigationExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(InvestigationExceptionHandler.class);

    @ExceptionHandler(InvestigationException.class)
    ResponseEntity<ApiError> domain(InvestigationException exception) {
        int status = switch (exception.code()) {
            case "NOT_FOUND" -> 404;
            case "REQUEST_KEY_CONFLICT" -> 409;
            default -> 400;
        };
        return ResponseEntity.status(status).body(new ApiError(exception.code(), exception.getMessage(), false));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> invalidJson() {
        return ResponseEntity.badRequest().body(new ApiError("INVALID_REQUEST", "Invalid investigation request", false));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiError> unsupportedMediaType() {
        return ResponseEntity.status(415).body(new ApiError("INVALID_REQUEST", "Request content type is not supported", false));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> unsupportedMethod() {
        return ResponseEntity.status(405).body(new ApiError("INVALID_REQUEST", "HTTP method is not supported for this resource", false));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> missingResource() {
        return ResponseEntity.status(404).body(new ApiError("NOT_FOUND", "Resource was not found", false));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception exception) {
        // SQL and parsing exceptions may contain the submitted input. Do not log the payload.
        log.error("Investigation API failed: {}", exception.getClass().getSimpleName());
        return ResponseEntity.internalServerError().body(new ApiError("INTERNAL_ERROR", "Investigation request failed", false));
    }
}
