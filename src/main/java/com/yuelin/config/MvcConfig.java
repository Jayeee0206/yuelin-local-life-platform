package com.yuelin.config;

import com.yuelin.utils.AdminInterceptor;
import com.yuelin.utils.LoginInterceptor;
import com.yuelin.utils.RefreshTokenInterceptor;
import com.yuelin.utils.SystemConstants;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;
import java.nio.file.Paths;

@Configuration
public class MvcConfig implements WebMvcConfigurer {
    @Resource
    private RefreshTokenInterceptor refreshTokenInterceptor;

    @Resource
    private AdminInterceptor adminInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(refreshTokenInterceptor).order(0);

        registry.addInterceptor(new LoginInterceptor())
                .excludePathPatterns("/user/login",
                        "/user/code",
                        "/blog/hot",
                        "/blog/*",
                        "/blog/likes/*",
                        "/blog/of/user",
                        "/blog-comments/of/blog/**",
                        "/blog-comments/replies/*",
                        "/user/public/**",
                        "/user/public-info/**",
                        "/shop/*",
                        "/shop/of/type",
                        "/shop/of/name",
                        "/shop-type/**",
                        "/uploads/**",
                        "/voucher/list/*")
                .order(1);

        registry.addInterceptor(adminInterceptor)
                .addPathPatterns("/shop", "/shop/", "/voucher", "/voucher/", "/voucher/seckill",
                        "/blog-comment-report/admin/**")
                .order(2);
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String uploadLocation = Paths.get(SystemConstants.IMAGE_UPLOAD_DIR)
                .toAbsolutePath()
                .normalize()
                .toUri()
                .toString();
        if (!uploadLocation.endsWith("/")) {
            uploadLocation += "/";
        }
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations(uploadLocation);
    }
}
