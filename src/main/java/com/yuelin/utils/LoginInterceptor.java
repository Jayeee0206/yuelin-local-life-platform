package com.yuelin.utils;

import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.DispatcherType;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // Preserve the original status on the container's internal error dispatch.
        // Direct /error requests and error dispatches to business handlers remain protected.
        if (request.getDispatcherType() == DispatcherType.ERROR
                && (request.getContextPath() + "/error").equals(request.getRequestURI())) {
            return true;
        }
//        1.判断是否需要拦截
        if (UserHolder.getUser()==null)
        {
            response.setStatus(401);
            return false;
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        //移除threadlocal用户，避免内存泄漏
        UserHolder.removeUser();
    }
}
