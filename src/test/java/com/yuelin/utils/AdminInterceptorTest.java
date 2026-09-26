package com.yuelin.utils;

import com.yuelin.dto.UserDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminInterceptorTest {

    private final AdminInterceptor interceptor = new AdminInterceptor("1, 9");

    @AfterEach
    void cleanUser() {
        UserHolder.removeUser();
    }

    @Test
    void rejectsAuthenticatedNonAdminUserWithForbiddenStatus() {
        UserDTO user = new UserDTO();
        user.setId(2L);
        UserHolder.saveUser(user);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(new MockHttpServletRequest(), response, new Object()));
        assertEquals(403, response.getStatus());
    }

    @Test
    void permitsConfiguredAdminUser() {
        UserDTO user = new UserDTO();
        user.setId(9L);
        UserHolder.saveUser(user);

        assertTrue(interceptor.preHandle(
                new MockHttpServletRequest(), new MockHttpServletResponse(), new Object()));
    }
}
