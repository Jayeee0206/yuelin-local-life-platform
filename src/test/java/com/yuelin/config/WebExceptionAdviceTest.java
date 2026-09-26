package com.yuelin.config;

import com.yuelin.dto.Result;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class WebExceptionAdviceTest {

    private final WebExceptionAdvice advice = new WebExceptionAdvice();

    @Test
    void runtimeFailuresUseHttp500InsteadOfSuccessfulHttpStatus() {
        ResponseEntity<Result> response = advice.handleRuntimeException(new IllegalStateException("boom"));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertFalse(response.getBody().getSuccess());
    }

    @Test
    void invalidArgumentsUseHttp400() {
        ResponseEntity<Result> response = advice.handleIllegalArgument(new IllegalArgumentException("bad input"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("bad input", response.getBody().getErrorMsg());
    }
}
