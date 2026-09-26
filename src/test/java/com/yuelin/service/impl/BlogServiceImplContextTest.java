package com.yuelin.service.impl;

import com.yuelin.mapper.BlogMapper;
import com.yuelin.mapper.ShopMapper;
import com.yuelin.service.ContentLikeService;
import com.yuelin.service.UploadAssetService;
import com.yuelin.service.BlogPublicationService;
import com.yuelin.service.IFollowService;
import com.yuelin.service.IUserService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

class BlogServiceImplContextTest {

    @Test
    void createsBlogServiceWhenCircularReferencesAreDisabled() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.setAllowCircularReferences(false);

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(beanFactory)) {
            context.registerBean("userService", IUserService.class, () -> mock(IUserService.class));
            context.registerBean("stringRedisTemplate", StringRedisTemplate.class,
                    () -> mock(StringRedisTemplate.class));
            context.registerBean(IFollowService.class, () -> mock(IFollowService.class));
            context.registerBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class));
            context.registerBean(ContentLikeService.class, () -> mock(ContentLikeService.class));
            context.registerBean(UploadAssetService.class);
            context.registerBean(BlogPublicationService.class);
            context.registerBean(BlogMapper.class, () -> mock(BlogMapper.class));
            context.registerBean(ShopMapper.class, () -> mock(ShopMapper.class));
            context.registerBean(BlogServiceImpl.class);
            context.refresh();

            assertNotNull(context.getBean(BlogServiceImpl.class));
        }
    }
}
