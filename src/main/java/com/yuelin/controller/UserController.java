package com.yuelin.controller;


import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.yuelin.dto.LoginFormDTO;
import com.yuelin.dto.ProfileUpdateDTO;
import com.yuelin.dto.Result;
import com.yuelin.service.UploadAssetService;
import com.yuelin.dto.UserDTO;
import com.yuelin.entity.User;
import com.yuelin.entity.UserInfo;
import com.yuelin.service.IUserInfoService;
import com.yuelin.service.IUserService;
import com.yuelin.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;

import static com.yuelin.utils.RedisConstants.LOGIN_USER_KEY;

@Slf4j
@RestController
@RequestMapping("/user")
public class UserController {

    @Resource
    private IUserService userService;

    @Resource
    private IUserInfoService userInfoService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 生成登录验证码（dev Profile 写入本地日志）
     */
    @PostMapping("code")
    public Result sendCode(@RequestParam("phone") String phone, HttpSession session) {
        return userService.sendCode(phone, session);
    }

    /**
     * 登录功能
     * @param loginForm 登录参数，包含手机号和验证码
     */
    @PostMapping("/login")
    public Result login(@RequestBody LoginFormDTO loginForm, HttpSession session){
        // 实现登录功能
        return userService.login(loginForm,session);
    }

    /**
     * 登出功能
     * @return 无
     */
    @PostMapping("/logout")
    public Result logout(@RequestHeader(value = "authorization", required = false) String token) {
        return userService.logout(token);
    }

    @GetMapping("/me")
    public Result me(){
        //  获取当前登录的用户并返回
        UserDTO user = UserHolder.getUser();
        return Result.ok(user);
    }

    @GetMapping("/info/{id}")
    public Result info(@PathVariable("id") Long userId){
        UserDTO current = UserHolder.getUser();
        if (current == null || !current.getId().equals(userId)) {
            return Result.fail("无权查看他人的完整资料");
        }
        UserInfo info = userInfoService.getById(userId);
        if (info == null) {
            // 没有详情，应该是第一次查看详情
            return Result.ok();
        }
        info.setCreateTime(null);
        info.setUpdateTime(null);
        // 返回
        return Result.ok(info);
    }

    @GetMapping("/public-info/{id}")
    public Result publicInfo(@PathVariable("id") Long userId) {
        UserInfo profile = userInfoService.getById(userId);
        Map<String, Object> publicProfile = new HashMap<>();
        if (profile != null) {
            publicProfile.put("city", profile.getCity());
            publicProfile.put("introduce", profile.getIntroduce());
        }
        return Result.ok(publicProfile);
    }

    @GetMapping("/public/{id}")
    public Result publicUser(@PathVariable("id") Long userId) {
        return queryById(userId);
    }

    @GetMapping("/{id}")
    public Result queryById(@PathVariable("id") Long userId) {
        // 查询详情
        User user = userService.getById(userId);
        if (user == null) {
            // 没有详情，应该是第一次查看详情
            return Result.ok();
        }
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        // 返回
        return Result.ok(userDTO);
    }

    @Resource
    private UploadAssetService uploadAssetService;

    @Transactional
    @PutMapping("/profile")
    public Result updateProfile(@RequestBody ProfileUpdateDTO form,
                                @RequestHeader(value = "authorization", required = false) String token) {
        UserDTO current = UserHolder.getUser();
        if (current == null) return Result.fail("请先登录");
        String nickName = StrUtil.trim(form.getNickName());
        if (StrUtil.isBlank(nickName) || nickName.length() > 32) {
            return Result.fail("昵称长度应为1到32个字符");
        }
        String introduce = StrUtil.trim(form.getIntroduce());
        if (introduce != null && introduce.length() > 128) {
            return Result.fail("个人介绍不能超过128个字符");
        }
        String city = StrUtil.trim(form.getCity());
        if (city != null && city.length() > 64) {
            return Result.fail("城市名称不能超过64个字符");
        }
        String icon = StrUtil.trim(form.getIcon());
        if (StrUtil.isNotBlank(icon) && !icon.startsWith("/uploads/") && !icon.startsWith("/imgs/")) {
            return Result.fail("头像地址不合法");
        }

        uploadAssetService.bindAvatar(icon, current.getId());
        User update = new User().setId(current.getId()).setNickName(nickName).setIcon(icon == null ? "" : icon);
        if (!userService.updateById(update)) {
            throw new IllegalStateException("用户资料更新失败");
        }

        UserInfo info = userInfoService.getById(current.getId());
        if (info == null) info = new UserInfo().setUserId(current.getId());
        info.setCity(city);
        info.setIntroduce(introduce);
        info.setGender(form.getGender());
        info.setBirthday(form.getBirthday());
        if (!userInfoService.saveOrUpdate(info)) {
            throw new IllegalStateException("用户详细资料更新失败");
        }

        if (StrUtil.isNotBlank(token)) {
            Runnable cacheUpdate = () -> updateCachedProfile(token, nickName, icon);
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        cacheUpdate.run();
                    }
                });
            } else {
                cacheUpdate.run();
            }
        }
        return Result.ok();
    }

    private void updateCachedProfile(String token, String nickName, String icon) {
        try {
            Map<String, String> values = new HashMap<>();
            values.put("nickName", nickName);
            values.put("icon", icon == null ? "" : icon);
            stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token, values);
        } catch (RuntimeException cacheError) {
            log.warn("数据库资料已更新，但登录缓存刷新失败", cacheError);
        }
    }

    //签到
    @PostMapping("/sign")
    public Result sign(){
        return userService.sign();
    }

    //统计每月签到
    @GetMapping("/sign/count")
    public Result signCount(){
        return userService.signCount();
    }
}
