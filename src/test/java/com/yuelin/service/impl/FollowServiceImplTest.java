package com.yuelin.service.impl;

import com.yuelin.dto.Result;
import com.yuelin.dto.UserDTO;
import com.yuelin.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class FollowServiceImplTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @InjectMocks
    private FollowServiceImpl service;

    @AfterEach
    void cleanUser() {
        UserHolder.removeUser();
    }

    @Test
    void rejectsFollowingSelfBeforeTouchingPersistenceOrRedis() {
        UserDTO user = new UserDTO();
        user.setId(8L);
        UserHolder.saveUser(user);

        Result result = service.follow(8L, true);

        assertFalse(result.getSuccess());
        verifyNoInteractions(redisTemplate);
    }
}
