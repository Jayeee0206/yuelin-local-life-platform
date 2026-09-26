package com.yuelin.controller;


import com.yuelin.dto.Result;
import com.yuelin.service.IFollowService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

@RestController
@RequestMapping("/follow")
public class FollowController {
    @Resource
    private IFollowService followService;
    //判断当前用户是否关注了该博主,加载页面的时候就会发起请求
    @GetMapping("/or/not/{id}")
    public Result isFollow(@PathVariable("id") Long followUserId) {
        return followService.isFollow(followUserId);
    }

    //实现取关/关注
    @PutMapping("/{id}/{isFollow}")
    public Result follow(@PathVariable("id") Long followUserId, @PathVariable("isFollow") Boolean isFellow) {
        return followService.follow(followUserId,isFellow);
    }

//    共同关注代码
    @GetMapping("/common/{id}")
    public Result followCommons(@PathVariable Long id){
        return followService.followCommons(id);
    }
}
