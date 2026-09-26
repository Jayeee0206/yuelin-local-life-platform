package com.yuelin.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yuelin.dto.Result;
import com.yuelin.dto.ScrollResult;
import com.yuelin.dto.UserDTO;
import com.yuelin.entity.Blog;
import com.yuelin.entity.Follow;
import com.yuelin.entity.User;
import com.yuelin.mapper.BlogMapper;
import com.yuelin.service.IBlogService;
import com.yuelin.service.ContentLikeService;
import com.yuelin.service.BlogPublicationService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yuelin.service.IFollowService;
import com.yuelin.service.IUserService;
import com.yuelin.utils.SystemConstants;
import com.yuelin.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.yuelin.utils.RedisConstants.FEED_KEY;

@Slf4j
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Resource
    private BlogPublicationService publicationService;

    @Resource
    private ContentLikeService contentLikeService;

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private IFollowService followService;

    @Override
    public Result queryById(Long id) {
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("博客不存在或已被删除");
        }
        queryBlogUser(blog);
        //追加判断blog是否被当前用户点赞，逻辑封装到isBlogLiked方法中
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            queryBlogUser(blog);
            //追加判断blog是否被当前用户点赞，逻辑封装到isBlogLiked方法中
            isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    private void isBlogLiked(Blog blog) {
        //1. 获取当前用户信息
        UserDTO userDTO = UserHolder.getUser();
        //当用户未登录时，就不判断了，直接return结束逻辑
        if (userDTO == null) {
            return;
        }

        ContentLikeService.State state = contentLikeService.state(ContentLikeService.Target.BLOG, blog.getId(), userDTO.getId());
        if (state != null) {
            blog.setIsLike(state.isLike());
            blog.setLiked(state.getLiked());
        }
    }

    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        if (user == null) {
            blog.setName("已注销用户");
            blog.setIcon("");
            return;
        }
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

    @Override
    public Result likeBlog(Long id) {
        UserDTO user = UserHolder.getUser();
        if (id == null || id <= 0) { return Result.fail("博客ID无效"); }
        if (user == null || user.getId() == null) { return Result.fail("请先登录"); }
        ContentLikeService.State state = contentLikeService.toggle(ContentLikeService.Target.BLOG, id, user.getId());
        return state == null ? Result.fail("博客不存在或已被删除") : Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        if (id == null || id <= 0) { return Result.fail("博客ID无效"); }
        List<Long> ids = contentLikeService.firstLikers(id);
        if (ids.isEmpty()) { return Result.ok(Collections.emptyList()); }
        String idsStr = StrUtil.join(",", ids);
        //select * from tb_user where id in (ids[0], ids[1] ...) order by field(id, ids[0], ids[1] ...)
        List<UserDTO> userDTOS = userService.query().in("id", ids)
                .last("order by field(id," + idsStr + ")")
                .list().stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDTOS);
    }

    @Override
    public Result saveBlog(Blog blog) {
        UserDTO user = UserHolder.getUser();
        if (user == null || user.getId() == null) { return Result.fail("请先登录"); }
        publicationService.publish(blog, user.getId());
        // The database transaction has committed. Feed delivery is best-effort and must not
        // turn a successful publication into an error response.
        try {
            List<Follow> followUsers = followService.query().eq("follow_user_id", user.getId()).list();
            long publishedAt = System.currentTimeMillis();
            for (Follow follow : followUsers) {
                String key = FEED_KEY + follow.getUserId();
                stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), publishedAt);
            }
        } catch (RuntimeException fanoutFailure) {
            log.warn("Blog {} committed but follower feed fanout failed", blog.getId(), fanoutFailure);
        }

        // 返回id
        return Result.ok(String.valueOf(blog.getId()));
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        //1. 获取当前用户
        Long userId = UserHolder.getUser().getId();
        //2. 查询该用户收件箱（之前我们存的key是固定前缀 + 粉丝id），所以根据当前用户id就可以查询是否有关注的人发了笔记
        String key = FEED_KEY + userId;
        Set<ZSetOperations.TypedTuple<String>> typeTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        //3. 非空判断
        if (typeTuples == null || typeTuples.isEmpty()) {
            return Result.ok(emptyScrollResult());
        }
        //4. 解析数据，blogId、minTime（时间戳）、offset，这里指定创建的list大小，可以略微提高效率，因为我们知道这个list就得是这么大
        ArrayList<Long> ids = new ArrayList<>(typeTuples.size());
        long minTime = 0;
        int os = 1;
        for (ZSetOperations.TypedTuple<String> typeTuple : typeTuples) {
            String id = typeTuple.getValue();
            Double score = typeTuple.getScore();
            if (id == null || score == null) {
                continue;
            }
            try {
                ids.add(Long.valueOf(id));
            } catch (NumberFormatException malformedBlogId) {
                continue;
            }
            //4.2 获取score（时间戳）
            long time = score.longValue();
            if (time == minTime){
                os++;
            }else {
                minTime = time;
                os = 1;
            }
        }
        if (ids.isEmpty()) {
            return Result.ok(emptyScrollResult());
        }
        //解决SQL的in不能排序问题，手动指定排序为传入的ids
        String idsStr = StrUtil.join(",", ids);

        //5. 根据id查询blog
        List<Blog> blogs = query().in("id", ids).last("ORDER BY FIELD(id," + idsStr + ")").list();

        for (Blog blog : blogs) {
            //5.1 查询发布该blog的用户信息
            queryBlogUser(blog);
            //5.2 查询当前用户是否给该blog点过赞
            isBlogLiked(blog);
        }
        //6. 封装结果并返回
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogs);
        scrollResult.setOffset(os);
        scrollResult.setMinTime(minTime);
        return Result.ok(scrollResult);
    }

    private ScrollResult emptyScrollResult() {
        ScrollResult result = new ScrollResult();
        result.setList(Collections.emptyList());
        result.setMinTime(0L);
        result.setOffset(0);
        return result;
    }
}
