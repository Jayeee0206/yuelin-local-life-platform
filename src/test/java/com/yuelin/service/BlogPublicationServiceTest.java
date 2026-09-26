package com.yuelin.service;

import com.yuelin.entity.Blog;
import com.yuelin.entity.Shop;
import com.yuelin.mapper.BlogMapper;
import com.yuelin.mapper.ShopMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BlogPublicationServiceTest {
    @Mock private BlogMapper blogMapper;
    @Mock private ShopMapper shopMapper;
    @Mock private UploadAssetService assets;
    private BlogPublicationService service;

    @BeforeEach
    void setUp() {
        service = new BlogPublicationService();
        ReflectionTestUtils.setField(service, "blogMapper", blogMapper);
        ReflectionTestUtils.setField(service, "shopMapper", shopMapper);
        ReflectionTestUtils.setField(service, "uploadAssetService", assets);
    }

    @Test
    void rejectsBlankAndOversizedContentBeforeAnyWrite() {
        Blog blank = validBlog().setTitle("  ");
        assertThrows(IllegalArgumentException.class, () -> service.publish(blank, 7L));
        Blog oversized = validBlog().setContent("x".repeat(BlogPublicationService.MAX_CONTENT_LENGTH + 1));
        assertThrows(IllegalArgumentException.class, () -> service.publish(oversized, 7L));
        verifyNoInteractions(blogMapper, shopMapper, assets);
    }

    @Test
    void rejectsUnknownShopBeforeInsert() {
        when(shopMapper.selectById(3L)).thenReturn(null);
        assertThrows(IllegalArgumentException.class, () -> service.publish(validBlog(), 7L));
        verify(blogMapper, never()).insert(any());
        verifyNoInteractions(assets);
    }

    @Test
    void normalizesFieldsAndIgnoresClientOwnershipCountersAndTimes() {
        when(shopMapper.selectById(3L)).thenReturn(new Shop().setId(3L));
        when(blogMapper.insert(any(Blog.class))).thenAnswer(invocation -> {
            invocation.<Blog>getArgument(0).setId(42L);
            return 1;
        });
        when(assets.attach("draft.png", 7L, 42L)).thenReturn("draft.png");
        LocalDateTime supplied = LocalDateTime.of(2020, 1, 1, 0, 0);
        Blog blog = validBlog()
                .setId(999L)
                .setUserId(999L)
                .setLiked(99)
                .setComments(99)
                .setCreateTime(supplied)
                .setUpdateTime(supplied)
                .setTitle("  title  ")
                .setContent("  content  ");

        service.publish(blog, 7L);

        assertEquals(42L, blog.getId());
        assertEquals(7L, blog.getUserId());
        assertEquals("title", blog.getTitle());
        assertEquals("content", blog.getContent());
        assertEquals(0, blog.getLiked());
        assertEquals(0, blog.getComments());
        assertNull(blog.getCreateTime());
        assertNull(blog.getUpdateTime());
    }

    private Blog validBlog() {
        return new Blog().setShopId(3L).setTitle("title").setContent("content").setImages("draft.png");
    }
}
