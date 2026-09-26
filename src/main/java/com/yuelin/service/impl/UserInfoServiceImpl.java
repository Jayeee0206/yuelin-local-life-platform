package com.yuelin.service.impl;

import com.yuelin.entity.UserInfo;
import com.yuelin.mapper.UserInfoMapper;
import com.yuelin.service.IUserInfoService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class UserInfoServiceImpl extends ServiceImpl<UserInfoMapper, UserInfo> implements IUserInfoService {

}
