package com.yuelin.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yuelin.dto.Result;
import com.yuelin.dto.UserDTO;
import com.yuelin.entity.Follow;
import com.yuelin.mapper.FollowMapper;
import com.yuelin.service.IFollowService;
import com.yuelin.service.IUserService;
import com.yuelin.utils.UserHolder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 关注关系服务。数据库是权威数据源，Redis Set 仅用于加速。
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    private static final String FOLLOWS_KEY_PREFIX = "follows:";

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;

    @Override
    @Transactional
    public Result follow(Long followUserId, Boolean isFellow) {
        Long userId = UserHolder.getUser().getId();
        if (followUserId == null || isFellow == null) {
            return Result.fail("关注参数不能为空");
        }
        if (Objects.equals(userId, followUserId)) {
            return Result.fail("不能关注自己");
        }

        String key = FOLLOWS_KEY_PREFIX + userId;
        if (isFellow) {
            if (userService.getById(followUserId) == null) {
                return Result.fail("目标用户不存在");
            }
            long existing = query().eq("user_id", userId)
                    .eq("follow_user_id", followUserId)
                    .count();
            if (existing == 0) {
                Follow follow = new Follow();
                follow.setUserId(userId);
                follow.setFollowUserId(followUserId);
                try {
                    if (!save(follow)) {
                        return Result.fail("关注失败");
                    }
                } catch (DuplicateKeyException concurrentDuplicate) {
                    // 只吞掉已由并发请求创建的同一关系，其他约束冲突继续上抛。
                    long persisted = query().eq("user_id", userId)
                            .eq("follow_user_id", followUserId)
                            .count();
                    if (persisted == 0) {
                        throw concurrentDuplicate;
                    }
                }
            }
            stringRedisTemplate.opsForSet().add(key, followUserId.toString());
        } else {
            remove(new QueryWrapper<Follow>().eq("user_id", userId)
                    .eq("follow_user_id", followUserId));
            // 取消关注是幂等操作，数据库中已不存在时也清理可能残留的缓存。
            stringRedisTemplate.opsForSet().remove(key, followUserId.toString());
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long followUserId) {
        if (followUserId == null) {
            return Result.ok(false);
        }
        Long userId = UserHolder.getUser().getId();
        long count = query().eq("user_id", userId)
                .eq("follow_user_id", followUserId).count();
        return Result.ok(count > 0);
    }

    @Override
    public Result followCommons(Long id) {
        Long userId = UserHolder.getUser().getId();
        if (id == null) {
            return Result.fail("用户ID不能为空");
        }

        // Redis Set 可能因重启或淘汰丢失，因此共同关注始终以数据库关系为准。
        List<Long> myFollowIds = list(new QueryWrapper<Follow>()
                .select("follow_user_id")
                .eq("user_id", userId)).stream()
                .map(Follow::getFollowUserId)
                .collect(Collectors.toList());
        if (myFollowIds.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }

        List<Long> commonIds = list(new QueryWrapper<Follow>()
                .select("follow_user_id")
                .eq("user_id", id)
                .in("follow_user_id", myFollowIds)).stream()
                .map(Follow::getFollowUserId)
                .collect(Collectors.toList());
        if (commonIds.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }

        List<UserDTO> users = userService.listByIds(commonIds).stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(users);
    }
}
