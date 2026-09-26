package com.yuelin.config;

import com.yuelin.dto.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class WebExceptionAdvice {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Result> handleIllegalArgument(IllegalArgumentException exception) {
        log.warn("Invalid request: {}", exception.getMessage());
        return ResponseEntity.badRequest().body(Result.fail(exception.getMessage()));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Result> handleRuntimeException(RuntimeException exception) {
        log.error("Unhandled server error", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.fail("服务器异常"));
    }
}
