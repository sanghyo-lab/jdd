package com.jdd.commerce.api;

import com.jdd.commerce.common.CommerceException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class CommerceErrors {
    private static final Logger LOG = LoggerFactory.getLogger(CommerceErrors.class);
    @ExceptionHandler(CommerceException.class)
    ResponseEntity<Map<String, Object>> domain(CommerceException error) {
        return response(error.status(), error.code(), error.getMessage(), error.retryable());
    }
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<Map<String, Object>> input(Exception error) {
        return response(400, "INVALID_INPUT", "Request body or parameter has an invalid type", false);
    }
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<Map<String, Object>> method(Exception error) {
        return response(405, "INVALID_INPUT", "HTTP method is not supported for this resource", false);
    }
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<Map<String, Object>> media(Exception error) {
        return response(415, "INVALID_INPUT", "Request content type is not supported", false);
    }
    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<Map<String, Object>> missing(Exception error) {
        return response(404, "NOT_FOUND", "Resource was not found", false);
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> unexpected(Exception error) {
        LOG.error("Commerce request failed ({})", error.getClass().getSimpleName());
        return response(500, "INTERNAL_ERROR", "Commerce request could not be completed", false);
    }
    private ResponseEntity<Map<String, Object>> response(int status, String code, String message, boolean retryable) {
        return ResponseEntity.status(status).body(Map.of("code", code, "message", message, "retryable", retryable));
    }
}
