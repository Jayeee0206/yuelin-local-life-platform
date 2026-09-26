package com.yuelin.utils;

import javax.servlet.DispatcherType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.junit.jupiter.api.Assertions.*;

class LoginInterceptorTest {
    private final LoginInterceptor interceptor = new LoginInterceptor();
    @AfterEach void clear() { UserHolder.removeUser(); }

    @Test void internalErrorDispatchPreservesNotFoundStatus() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/app/error");
        request.setContextPath("/app"); request.setDispatcherType(DispatcherType.ERROR);
        MockHttpServletResponse response = new MockHttpServletResponse(); response.setStatus(404);
        assertTrue(interceptor.preHandle(request, response, new Object()));
        assertEquals(404, response.getStatus());
    }
    @Test void directErrorRequestDoesNotBypassLogin() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(401, response.getStatus());
    }
    @Test void errorDispatchToBusinessHandlerDoesNotBypassLogin() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/blog");
        request.setDispatcherType(DispatcherType.ERROR);
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(401, response.getStatus());
    }
}
