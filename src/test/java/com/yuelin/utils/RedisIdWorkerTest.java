package com.yuelin.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisIdWorkerTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @InjectMocks
    private RedisIdWorker idWorker;

    @Test
    void rejectsMissingRedisSequenceInsteadOfThrowingNullPointerException() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(startsWith("inc:order:"))).thenReturn(null);

        assertThrows(IllegalStateException.class, () -> idWorker.nextId("order"));
    }
}
