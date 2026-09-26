package com.yuelin.service.impl;

import com.yuelin.dto.Result;
import com.yuelin.dto.ScrollResult;
import com.yuelin.dto.UserDTO;
import com.yuelin.entity.Blog;
import com.yuelin.service.BlogPublicationService;
import com.yuelin.service.IFollowService;
import com.yuelin.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BlogServiceImplTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ZSetOperations<String, String> zSetOperations;
    @Mock private BlogPublicationService publicationService;
    @Mock private IFollowService followService;
    @InjectMocks private BlogServiceImpl service;

    @AfterEach
    void cleanUser() {
        UserHolder.removeUser();
    }

    @Test
    void emptyFollowFeedKeepsStableScrollResultShape() {
        login(7L);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(zSetOperations.reverseRangeByScoreWithScores(anyString(), anyDouble(), anyDouble(), anyLong(), anyLong()))
                .thenReturn(Collections.emptySet());

        Result result = service.queryBlogOfFollow(System.currentTimeMillis(), 0);

        ScrollResult scroll = assertInstanceOf(ScrollResult.class, result.getData());
        assertEquals(Collections.emptyList(), scroll.getList());
        assertEquals(0L, scroll.getMinTime());
        assertEquals(0, scroll.getOffset());
    }

    @Test
    void committedPublicationStillSucceedsWhenFeedFanoutFails() {
        login(7L);
        Blog blog = new Blog().setTitle("valid");
        doAnswer(invocation -> {
            blog.setId(9007199254740993L);
            return null;
        }).when(publicationService).publish(same(blog), eq(7L));
        when(followService.query()).thenThrow(new IllegalStateException("redis or follower query unavailable"));

        Result result = service.saveBlog(blog);

        assertTrue(result.getSuccess());
        assertEquals("9007199254740993", result.getData());
    }

    private void login(long id) {
        UserDTO user = new UserDTO();
        user.setId(id);
        UserHolder.saveUser(user);
    }
}
