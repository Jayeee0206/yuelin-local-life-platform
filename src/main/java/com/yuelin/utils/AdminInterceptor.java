package com.yuelin.utils;

import com.yuelin.dto.UserDTO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;

/** Restricts management APIs to the server-configured user-id allowlist. */
@Component
public class AdminInterceptor implements HandlerInterceptor {

    private final Set<Long> adminUserIds;

    public AdminInterceptor(@Value("${yuelin.security.admin-user-ids:}") String configuredIds) {
        if (configuredIds == null || configuredIds.trim().isEmpty()) {
            this.adminUserIds = Collections.emptySet();
        } else {
            this.adminUserIds = Collections.unmodifiableSet(Arrays.stream(configuredIds.split(","))
                    .map(String::trim)
                    .filter(value -> !value.isEmpty())
                    .map(Long::valueOf)
                    .collect(Collectors.toSet()));
        }
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        UserDTO user = UserHolder.getUser();
        if (user == null || !adminUserIds.contains(user.getId())) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return false;
        }
        return true;
    }
}
