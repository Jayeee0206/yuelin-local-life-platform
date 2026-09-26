// SYNTHETIC_PHONE_FIXTURES_ONLY: phone-shaped values below are generated test fixtures.
package com.yuelin.service.impl;

import com.yuelin.dto.LoginFormDTO;
import com.yuelin.dto.Result;
import com.yuelin.dto.UserDTO;
import com.yuelin.entity.User;
import com.yuelin.mapper.UserMapper;
import com.yuelin.service.verification.VerificationCodeSender;
import com.yuelin.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;

import static com.yuelin.utils.RedisConstants.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private VerificationCodeSender verificationCodeSender;
    @Mock
    private ZSetOperations<String, String> zSetOperations;
    @Mock
    private UserMapper userMapper;

    @InjectMocks
    private UserServiceImpl userService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(userService, "baseMapper", userMapper);
    }

    @AfterEach
    void cleanThreadLocal() {
        UserHolder.removeUser();
    }

    @Test
    void sendCodeRejectsInvalidPhoneBeforeUsingRedis() {
        Result result = userService.sendCode("not-a-phone", null);
        assertFalse(result.getSuccess());
        verifyNoInteractions(redisTemplate);
    }

    @Test
    void sendCodeAtomicallyStoresCodeResetsAttemptsAndDispatches() {
        String phone = "13800138000";
        allowRateLimit(phone);
        when(redisTemplate.execute(any(DefaultRedisScript.class),
                eq(Arrays.asList(LOGIN_CODE_KEY + phone, LOGIN_CODE_ATTEMPT_KEY + phone)),
                matches("\\d{6}"), eq("120"))).thenReturn(1L);

        Result result = userService.sendCode(phone, null);

        assertTrue(result.getSuccess());
        verify(verificationCodeSender).send(eq(phone), matches("\\d{6}"));
        verify(redisTemplate).execute(any(DefaultRedisScript.class),
                eq(Arrays.asList(LOGIN_CODE_KEY + phone, LOGIN_CODE_ATTEMPT_KEY + phone)),
                matches("\\d{6}"), eq("120"));
    }

    @Test
    void sendCodeRevokesCodeAttemptsAndRateReservationWhenDispatchFails() {
        String phone = "13800138003";
        allowRateLimit(phone);
        when(redisTemplate.execute(any(DefaultRedisScript.class),
                eq(Arrays.asList(LOGIN_CODE_KEY + phone, LOGIN_CODE_ATTEMPT_KEY + phone)),
                anyString(), eq("120"))).thenReturn(1L);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        doThrow(new IllegalStateException("sender offline"))
                .when(verificationCodeSender).send(eq(phone), anyString());

        assertThrows(IllegalStateException.class, () -> userService.sendCode(phone, null));

        verify(redisTemplate).delete(Arrays.asList(
                LOGIN_CODE_KEY + phone,
                LOGIN_CODE_ATTEMPT_KEY + phone));
        verify(zSetOperations).remove(eq(SENDCODE_SENDTIME_KEY + phone), anyString());
    }

    @Test
    void sendCodeFailsClosedWhenAtomicRateLimitCannotReachRedis() {
        String phone = "13800138001";
        when(redisTemplate.execute(any(DefaultRedisScript.class),
                eq(Collections.singletonList(SENDCODE_SENDTIME_KEY + phone)),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(null);

        Result result = userService.sendCode(phone, null);

        assertFalse(result.getSuccess());
        verifyNoInteractions(verificationCodeSender);
    }

    @Test
    void loginRejectsWrongCodeWithoutQueryingDatabase() {
        LoginFormDTO form = loginForm("13800138002", "123456");
        when(redisTemplate.execute(any(DefaultRedisScript.class),
                eq(Arrays.asList(LOGIN_CODE_KEY + form.getPhone(), LOGIN_CODE_ATTEMPT_KEY + form.getPhone())),
                eq(form.getCode()), eq("5"))).thenReturn(0L);

        Result result = userService.login(form, null);

        assertFalse(result.getSuccess());
        verifyNoInteractions(userMapper);
    }

    @Test
    void loginRejectsCodeAfterAtomicAttemptLimit() {
        LoginFormDTO form = loginForm("13800138002", "123456");
        when(redisTemplate.execute(any(DefaultRedisScript.class),
                eq(Arrays.asList(LOGIN_CODE_KEY + form.getPhone(), LOGIN_CODE_ATTEMPT_KEY + form.getPhone())),
                eq(form.getCode()), eq("5"))).thenReturn(-2L);

        Result result = userService.login(form, null);

        assertFalse(result.getSuccess());
        assertTrue(result.getErrorMsg().contains("尝试次数过多"));
        verifyNoInteractions(userMapper);
    }

    @Test
    void successfulLoginCreatesHashAndTtlWithOneAtomicScript() {
        LoginFormDTO form = loginForm("13800138002", "123456");
        when(redisTemplate.execute(any(DefaultRedisScript.class),
                eq(Arrays.asList(LOGIN_CODE_KEY + form.getPhone(), LOGIN_CODE_ATTEMPT_KEY + form.getPhone())),
                eq(form.getCode()), eq("5"))).thenReturn(1L);
        User user = new User().setId(9007199254740993L).setPhone(form.getPhone()).setNickName("tester");
        when(userMapper.selectOne(any())).thenReturn(user);
        when(redisTemplate.execute(any(DefaultRedisScript.class),
                argThat(keys -> keys.size() == 1 && keys.get(0).startsWith(LOGIN_USER_KEY)),
                eq("9007199254740993"), eq("tester"), eq(""), eq("1800"))).thenReturn(1L);

        Result result = userService.login(form, null);

        assertTrue(result.getSuccess());
        assertInstanceOf(String.class, result.getData());
        verify(redisTemplate).execute(any(DefaultRedisScript.class),
                argThat(keys -> keys.size() == 1 && keys.get(0).startsWith(LOGIN_USER_KEY)),
                eq("9007199254740993"), eq("tester"), eq(""), eq("1800"));
        verify(redisTemplate, never()).expire(anyString(), anyLong(), any());
    }

    @Test
    void loginRejectsInvalidPhoneBeforeUsingRedis() {
        LoginFormDTO form = loginForm("demo@example.com", "123456");
        Result result = userService.login(form, null);
        assertFalse(result.getSuccess());
        verifyNoInteractions(redisTemplate);
    }

    @Test
    void loginRejectsNullForm() {
        assertFalse(userService.login(null, null).getSuccess());
        verifyNoInteractions(redisTemplate);
    }

    @Test
    void logoutDeletesTokenAndClearsCurrentUser() {
        UserHolder.saveUser(new UserDTO());
        Result result = userService.logout("token-123");
        assertNotNull(result);
        verify(redisTemplate).delete(LOGIN_USER_KEY + "token-123");
        assertNull(UserHolder.getUser());
    }

    @Test
    void logoutWithoutTokenIsIdempotent() {
        UserHolder.saveUser(new UserDTO());
        Result result = userService.logout("  ");
        assertNotNull(result);
        verify(redisTemplate, never()).delete(anyString());
        assertNull(UserHolder.getUser());
    }

    private void allowRateLimit(String phone) {
        when(redisTemplate.execute(any(DefaultRedisScript.class),
                eq(Collections.singletonList(SENDCODE_SENDTIME_KEY + phone)),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(0L);
    }

    private LoginFormDTO loginForm(String phone, String code) {
        LoginFormDTO form = new LoginFormDTO();
        form.setPhone(phone);
        form.setCode(code);
        return form;
    }
}
