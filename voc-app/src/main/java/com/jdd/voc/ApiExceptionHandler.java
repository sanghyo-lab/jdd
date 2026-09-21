package com.jdd.voc;

import com.jdd.voc.domain.VocFailure;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiExceptionHandler {
    public record ApiError(String code, String message, boolean retryable) {}
    @ExceptionHandler(VocFailure.class)
    public ResponseEntity<ApiError> domain(VocFailure error) {
        int status = switch (error.code()) {
            case "NOT_FOUND" -> 404;
            case "TICKET_VERSION_CONFLICT", "REQUEST_KEY_CONFLICT" -> 409;
            default -> 400;
        };
        return ResponseEntity.status(status).body(new ApiError(error.code(), error.getMessage(), false));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class})
    public ResponseEntity<ApiError> malformed(Exception error) {
        return ResponseEntity.badRequest().body(new ApiError("INVALID_REQUEST", "요청 형식을 확인해 주세요.", false));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> unsupportedMethod() {
        return ResponseEntity.status(405).body(new ApiError("INVALID_REQUEST", "지원하지 않는 요청 방식입니다.", false));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiError> unsupportedMediaType() {
        return ResponseEntity.status(415).body(new ApiError("INVALID_REQUEST", "지원하지 않는 본문 형식입니다.", false));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> missingResource() {
        return ResponseEntity.status(404).body(new ApiError("NOT_FOUND", "요청한 경로를 찾을 수 없습니다.", false));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception error) {
        return ResponseEntity.internalServerError().body(new ApiError("INTERNAL_ERROR", "요청을 처리하지 못했습니다.", false));
    }
}
