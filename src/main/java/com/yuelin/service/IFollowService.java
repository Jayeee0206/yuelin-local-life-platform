package com.yuelin.service;

import com.yuelin.dto.Result;
import com.yuelin.entity.Follow;
import com.baomidou.mybatisplus.extension.service.IService;

public interface IFollowService extends IService<Follow> {

    Result isFollow(Long followUserId);

    Result follow(Long followUserId, Boolean isFellow);

    Result followCommons(Long id);
}
