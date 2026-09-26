package com.yuelin.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.yuelin.dto.LoginFormDTO;
import com.yuelin.dto.Result;
import com.yuelin.entity.User;

import javax.servlet.http.HttpSession;

public interface IUserService extends IService<User> {

    Result sendCode(String phone, HttpSession session);

    Result login(LoginFormDTO loginForm, HttpSession session);

    Result logout(String token);

    Result sign();

    Result signCount();
}
