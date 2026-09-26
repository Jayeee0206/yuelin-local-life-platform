package com.yuelin.config;

import com.yuelin.utils.AdminInterceptor;
import com.yuelin.utils.LoginInterceptor;
import com.yuelin.utils.RefreshTokenInterceptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.handler.MappedInterceptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MvcConfigTest {

    private MappedInterceptor loginMapping;
    private MappedInterceptor adminMapping;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    @BeforeEach
    void setUp() {
        MvcConfig config = new MvcConfig();
        ReflectionTestUtils.setField(config, "refreshTokenInterceptor", new RefreshTokenInterceptor());
        ReflectionTestUtils.setField(config, "adminInterceptor", new AdminInterceptor("1"));
        ExposedInterceptorRegistry registry = new ExposedInterceptorRegistry();

        config.addInterceptors(registry);

        List<Object> interceptors = registry.exposedInterceptors();
        assertTrue(interceptors.stream().anyMatch(RefreshTokenInterceptor.class::isInstance));
        loginMapping = interceptors.stream()
                .filter(MappedInterceptor.class::isInstance)
                .map(MappedInterceptor.class::cast)
                .filter(mapped -> mapped.getInterceptor() instanceof LoginInterceptor)
                .findFirst()
                .orElseThrow();
        assertInstanceOf(LoginInterceptor.class, loginMapping.getInterceptor());
        adminMapping = interceptors.stream()
                .filter(MappedInterceptor.class::isInstance)
                .map(MappedInterceptor.class::cast)
                .filter(mapped -> mapped.getInterceptor() instanceof AdminInterceptor)
                .findFirst()
                .orElseThrow();
    }

    @Test
    void permitsReadOnlyPublicFrontendEndpoints() {
        assertPublic("/user/login");
        assertPublic("/user/code");
        assertPublic("/blog/hot");
        assertPublic("/blog/4");
        assertPublic("/blog/likes/4");
        assertPublic("/blog/of/user");
        assertPublic("/blog-comments/of/blog/4");
        assertPublic("/blog-comments/replies/4");
        assertPublic("/user/public/2");
        assertPublic("/user/public-info/2");
        assertPublic("/shop/1");
        assertPublic("/shop/of/type");
        assertPublic("/shop-type/list");
        assertPublic("/uploads/blogs/1/example.png");
        assertPublic("/voucher/list/1");
    }

    @Test
    void limitsManagementEndpointsToConfiguredAdministrators() {
        assertTrue(adminMapping.matches("/shop", pathMatcher));
        assertTrue(adminMapping.matches("/voucher", pathMatcher));
        assertTrue(adminMapping.matches("/voucher/seckill", pathMatcher));
        assertTrue(adminMapping.matches("/blog-comment-report/admin/pending", pathMatcher));
        assertTrue(adminMapping.matches("/blog-comment-report/admin/block/4", pathMatcher));
        assertFalse(adminMapping.matches("/blog-comment-report/4", pathMatcher));
        assertFalse(adminMapping.matches("/shop/1", pathMatcher));
        assertFalse(adminMapping.matches("/voucher/list/1", pathMatcher));
    }

    @Test
    void protectsPersonalAndMutatingFrontendEndpoints() {
        assertProtected("/user/me");
        assertProtected("/user/2");
        assertProtected("/user/info/2");
        assertProtected("/user/profile");
        assertProtected("/user/logout");
        assertProtected("/blog");
        assertProtected("/blog/like/4");
        assertProtected("/blog/of/me");
        assertProtected("/blog/of/follow");
        assertProtected("/blog-comments");
        assertProtected("/blog-comments/4");
        assertProtected("/blog-comments/like/4");
        assertProtected("/blog-comment-report/4");
        assertProtected("/blog-comment-report/admin/pending");
        assertProtected("/follow/or/not/2");
        assertProtected("/follow/2/true");
        assertProtected("/upload/blog");
        assertProtected("/shop");
        assertProtected("/voucher");
        assertProtected("/voucher/seckill");
        assertProtected("/voucher-order/seckill/1");
    }

    private void assertPublic(String path) {
        assertFalse(loginMapping.matches(path, pathMatcher), path + " should bypass the login interceptor");
    }

    private void assertProtected(String path) {
        assertTrue(loginMapping.matches(path, pathMatcher), path + " should require login");
    }

    private static final class ExposedInterceptorRegistry extends InterceptorRegistry {
        List<Object> exposedInterceptors() {
            return super.getInterceptors();
        }
    }
}
