package com.yuelin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yuelin.dto.BlogCommentDTO;
import com.yuelin.dto.BlogCommentSaveDTO;
import com.yuelin.dto.Result;
import com.yuelin.dto.UserDTO;
import com.yuelin.entity.Blog;
import com.yuelin.entity.BlogComments;
import com.yuelin.entity.User;
import com.yuelin.mapper.BlogCommentsMapper;
import com.yuelin.service.IBlogCommentsService;
import com.yuelin.service.IBlogService;
import com.yuelin.service.IUserService;
import com.yuelin.utils.CommentStatus;
import com.yuelin.utils.SystemConstants;
import com.yuelin.utils.UserHolder;
import com.yuelin.service.ContentLikeService;
import com.yuelin.service.CommentMutationGuard;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;


/**
 * <p>
 * 探店笔记评论服务实现。数据库是唯一权威数据源，不引入缓存，避免评论计数与列表不一致。
 * </p>
 */
@Service
public class BlogCommentsServiceImpl extends ServiceImpl<BlogCommentsMapper, BlogComments>
        implements IBlogCommentsService {

    /**
     * 与 tb_blog_comments.content 的 varchar(255) 保持一致。
     */
    static final int MAX_CONTENT_LENGTH = 255;

    /**
     * 单条一级评论默认预览的回复条数，避免一次返回过多楼中楼。
     */
    static final int MAX_PREVIEW_REPLIES = 3;

    /**
     * 一级评论的 parent_id 取值。
     */
    private static final long ROOT_PARENT_ID = 0L;

    private static final String DELETED_USER_NAME = "已注销用户";

    @Resource
    private IUserService userService;

    @Resource
    private IBlogService blogService;

    @Resource
    private ContentLikeService contentLikeService;

    @Resource
    private CommentMutationGuard mutationGuard;

    @Override
    public Result queryCommentsByBlogId(Long blogId, Integer current) {
        if (blogId == null || blogId <= 0) {
            return Result.fail("笔记ID无效");
        }
        int pageNo = current == null || current < 1 ? 1 : current;

        Long viewerId = currentUserId();
        Page<BlogComments> rootPage = query()
                .eq("blog_id", blogId)
                .eq("parent_id", ROOT_PARENT_ID)
                .and(wrapper -> applyVisibility(wrapper, viewerId))
                .orderByDesc("create_time")
                .orderByDesc("id")
                .page(new Page<>(pageNo, SystemConstants.MAX_PAGE_SIZE));

        List<BlogComments> roots = rootPage.getRecords();
        if (roots == null || roots.isEmpty()) {
            return Result.ok(Collections.emptyList(), rootPage.getTotal());
        }

        // Fixed root page (10) × per-root SQL page (3): never materialize every reply.
        List<BlogComments> replies = new ArrayList<>();
        Map<Long, Integer> replyCounts = new LinkedHashMap<>();
        for (BlogComments root : roots) {
            Page<BlogComments> preview = page(new Page<>(1, MAX_PREVIEW_REPLIES),
                    new QueryWrapper<BlogComments>()
                            .eq("blog_id", blogId).eq("parent_id", root.getId())
                            .and(wrapper -> applyVisibility(wrapper, viewerId))
                            .orderByAsc("create_time").orderByAsc("id"));
            replies.addAll(preview.getRecords());
            replyCounts.put(root.getId(), (int) Math.min(Integer.MAX_VALUE, preview.getTotal()));
        }
        Map<Long, BlogComments> answers = loadVisibleAnswers(replies, viewerId);
        List<BlogComments> userContext = new ArrayList<>(replies);
        userContext.addAll(answers.values());
        Map<Long, User> userIndex = loadUsers(roots, userContext);
        Map<Long, BlogCommentDTO> rootIndex = new LinkedHashMap<>();
        List<BlogCommentDTO> result = new ArrayList<>(roots.size());
        for (BlogComments root : roots) {
            BlogCommentDTO dto = toDTO(root, userIndex);
            dto.setReplyCount(replyCounts.get(root.getId()));
            rootIndex.put(root.getId(), dto);
            result.add(dto);
        }
        for (BlogComments reply : replies) {
            BlogCommentDTO parent = rootIndex.get(reply.getParentId());
            if (parent != null) {
                BlogCommentDTO dto = toDTO(reply, userIndex);
                decorateAnswer(dto, reply, answers, userIndex);
                parent.addReply(dto);
            }
        }

        markLikeStatus(collectAll(result));
        return Result.ok(result, rootPage.getTotal());
    }

    @Override
    public Result queryRepliesByParentId(Long parentId, Integer current) {
        if (parentId == null || parentId <= 0) {
            return Result.fail("评论ID无效");
        }
        int pageNo = current == null || current < 1 ? 1 : current;

        BlogComments parent = getById(parentId);
        if (parent == null) {
            return Result.fail("评论不存在或已被删除");
        }
        if (!Objects.equals(parent.getParentId(), ROOT_PARENT_ID)) {
            return Result.fail("只有一级评论才有回复列表");
        }

        Long viewerId = currentUserId();
        if (!canRead(parent, viewerId)) { return Result.fail("评论不存在或不可见"); }
        Page<BlogComments> replyPage = query()
                .eq("parent_id", parentId)
                .eq("blog_id", parent.getBlogId())
                .and(wrapper -> applyVisibility(wrapper, viewerId))
                .orderByAsc("create_time")
                .orderByAsc("id")
                .page(new Page<>(pageNo, SystemConstants.MAX_PAGE_SIZE));

        List<BlogComments> replies = replyPage.getRecords();
        if (replies == null || replies.isEmpty()) {
            return Result.ok(Collections.emptyList(), replyPage.getTotal());
        }

        Map<Long, BlogComments> answers = loadVisibleAnswers(replies, viewerId);
        List<BlogComments> userContext = new ArrayList<>(replies);
        userContext.addAll(answers.values());
        Map<Long, User> userIndex = loadUsers(Collections.singletonList(parent), userContext);
        List<BlogCommentDTO> result = new ArrayList<>(replies.size());
        for (BlogComments reply : replies) {
            BlogCommentDTO dto = toDTO(reply, userIndex);
            decorateAnswer(dto, reply, answers, userIndex);
            result.add(dto);
        }

        markLikeStatus(result);
        return Result.ok(result, replyPage.getTotal());
    }

    @Override
    @Transactional
    public Result likeComment(Long id) {
        if (id == null || id <= 0) {
            return Result.fail("评论ID无效");
        }
        UserDTO loginUser = UserHolder.getUser();
        if (loginUser == null || loginUser.getId() == null) {
            return Result.fail("请先登录");
        }
        BlogComments comment = getById(id);
        if (comment == null) {
            return Result.fail("评论不存在或已被删除");
        }

        if (!mutationGuard.lockBlog(comment.getBlogId())) { return Result.fail("评论不存在或不可见"); }
        Long rootId = Objects.equals(comment.getParentId(), ROOT_PARENT_ID) ? id : comment.getParentId();
        BlogComments root = mutationGuard.lockComment(rootId);
        if (!canRead(root, loginUser.getId()) || !Objects.equals(root.getParentId(), ROOT_PARENT_ID)
                || !Objects.equals(root.getBlogId(), comment.getBlogId())) {
            return Result.fail("评论不存在或不可见");
        }
        BlogComments current = Objects.equals(rootId, id) ? root : mutationGuard.lockComment(id);
        if (!canRead(current, loginUser.getId()) || !Objects.equals(current.getBlogId(), root.getBlogId())
                || !(Objects.equals(current.getId(), rootId) || Objects.equals(current.getParentId(), rootId))) {
            return Result.fail("评论不存在或不可见");
        }

        ContentLikeService.State state = contentLikeService.toggle(ContentLikeService.Target.COMMENT, id, loginUser.getId());
        if (state == null) { return Result.fail("评论不存在或已被删除"); }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", id.toString());
        payload.put("liked", state.getLiked());
        payload.put("isLike", state.isLike());
        return Result.ok(payload);
    }

    /**
     * 展开一级评论与其已装载回复，便于统一标记点赞状态。
     */
    private List<BlogCommentDTO> collectAll(List<BlogCommentDTO> roots) {
        List<BlogCommentDTO> all = new ArrayList<>(roots.size());
        for (BlogCommentDTO root : roots) {
            all.add(root);
            all.addAll(root.getReplies());
        }
        return all;
    }

    /**
     * 为当前登录用户标记点赞状态。游客不查询点赞关系，直接保持默认的未点赞。
     * 注意：getReplies() 返回副本，因此必须在回复装载完成之后再调用。
     */
    private void markLikeStatus(List<BlogCommentDTO> comments) {
        UserDTO loginUser = UserHolder.getUser();
        if (loginUser == null || loginUser.getId() == null || comments.isEmpty()) {
            return;
        }
        for (BlogCommentDTO comment : comments) {
            if (comment.getId() == null) {
                continue;
            }
            ContentLikeService.State state = contentLikeService.state(ContentLikeService.Target.COMMENT, comment.getId(), loginUser.getId());
            if (state != null) {
                comment.setIsLike(state.isLike());
                comment.setLiked(state.getLiked());
            }
        }
    }

    @Override
    @Transactional
    public Result saveComment(BlogCommentSaveDTO saveDTO) {
        if (saveDTO == null) {
            return Result.fail("评论内容不能为空");
        }
        UserDTO loginUser = UserHolder.getUser();
        if (loginUser == null || loginUser.getId() == null) {
            return Result.fail("请先登录");
        }
        Long blogId = saveDTO.getBlogId();
        if (blogId == null || blogId <= 0) {
            return Result.fail("笔记ID无效");
        }
        String content = saveDTO.getContent() == null ? "" : saveDTO.getContent().trim();
        if (content.isEmpty()) {
            return Result.fail("评论内容不能为空");
        }
        if (content.length() > MAX_CONTENT_LENGTH) {
            return Result.fail("评论内容不能超过" + MAX_CONTENT_LENGTH + "字");
        }

        Blog blog = blogService.getById(blogId);
        if (blog == null) {
            return Result.fail("笔记不存在或已被删除");
        }

        long parentId = normalizeId(saveDTO.getParentId());
        long answerId = normalizeId(saveDTO.getAnswerId());
        if (parentId == ROOT_PARENT_ID && answerId != ROOT_PARENT_ID) {
            return Result.fail("回复的评论不存在");
        }
        if (parentId != ROOT_PARENT_ID) {
            BlogComments parent = getById(parentId);
            if (parent == null || !Objects.equals(parent.getBlogId(), blogId)) {
                return Result.fail("回复的评论不存在");
            }
            if (!Objects.equals(parent.getParentId(), ROOT_PARENT_ID)) {
                return Result.fail("只支持对一级评论发起回复");
            }
            if (answerId != ROOT_PARENT_ID) {
                BlogComments answer = getById(answerId);
                boolean answerBelongsToThread = answer != null
                        && Objects.equals(answer.getBlogId(), blogId)
                        && (Objects.equals(answer.getId(), parentId)
                        || Objects.equals(answer.getParentId(), parentId));
                if (!answerBelongsToThread) {
                    return Result.fail("回复的评论不存在");
                }
            }
        }

        if (!mutationGuard.lockBlog(blogId)) { return Result.fail("笔记不存在或已被删除"); }
        if (parentId != ROOT_PARENT_ID) {
            BlogComments currentParent = mutationGuard.lockComment(parentId);
            if (!canRead(currentParent, loginUser.getId()) || !Objects.equals(currentParent.getBlogId(), blogId)
                    || !Objects.equals(currentParent.getParentId(), ROOT_PARENT_ID)) {
                return Result.fail("回复的评论不存在");
            }
            if (answerId != ROOT_PARENT_ID) {
                BlogComments currentAnswer = mutationGuard.lockComment(answerId);
                if (!canRead(currentAnswer, loginUser.getId()) || !Objects.equals(currentAnswer.getBlogId(), blogId)
                        || !(Objects.equals(currentAnswer.getId(), parentId)
                        || Objects.equals(currentAnswer.getParentId(), parentId))) {
                    return Result.fail("回复的评论不存在");
                }
            }
        }

        BlogComments comment = new BlogComments()
                .setBlogId(blogId)
                .setUserId(loginUser.getId())
                .setParentId(parentId)
                .setAnswerId(answerId)
                .setContent(content)
                .setLiked(0)
                .setStatus(CommentStatus.NORMAL)
                // 显式写入时间，保证保存后立即回显，不依赖数据库默认值。
                .setCreateTime(LocalDateTime.now())
                .setUpdateTime(LocalDateTime.now());
        if (!save(comment)) {
            return Result.fail("评论失败，请稍后重试");
        }

        // 评论数是展示字段，使用 SQL 自增避免并发覆盖。
        boolean counted = blogService.update()
                .setSql("comments = IFNULL(comments, 0) + 1")
                .eq("id", blogId)
                .update();
        if (!counted) {
            throw new IllegalStateException("Failed to increment blog comment count");
        }

        Map<Long, User> userIndex = loadUsers(Collections.singletonList(comment), Collections.emptyList());
        return Result.ok(toDTO(comment, userIndex));
    }

    @Override
    @Transactional
    public Result deleteComment(Long id) {
        if (id == null || id <= 0) {
            return Result.fail("评论ID无效");
        }
        UserDTO loginUser = UserHolder.getUser();
        if (loginUser == null || loginUser.getId() == null) {
            return Result.fail("请先登录");
        }
        BlogComments comment = getById(id);
        if (comment == null) {
            return Result.fail("评论不存在或已被删除");
        }
        if (!Objects.equals(comment.getUserId(), loginUser.getId())) {
            return Result.fail("只能删除自己的评论");
        }

        if (!mutationGuard.lockBlog(comment.getBlogId())) { return Result.fail("笔记不存在或已被删除"); }
        comment = mutationGuard.lockComment(id);
        if (comment == null) { return Result.fail("评论不存在或已被删除"); }
        if (!Objects.equals(comment.getUserId(), loginUser.getId())) { return Result.fail("只能删除自己的评论"); }
        int removed = 1;
        List<Long> removedIds = new ArrayList<>();
        removedIds.add(id);
        if (Objects.equals(comment.getParentId(), ROOT_PARENT_ID)) {
            // 一级评论被删除时，其楼中楼没有展示载体，需要一并清理。
            List<BlogComments> replies = mutationGuard.lockReplies(id);
            if (!replies.isEmpty()) {
                List<Long> replyIds = replies.stream().map(BlogComments::getId).collect(Collectors.toList());
                if (!removeByIds(replyIds)) {
                    throw new IllegalStateException("Failed to delete comment replies");
                }
                removed += replyIds.size();
                removedIds.addAll(replyIds);
            }
        }
        if (!removeById(id)) {
            throw new IllegalStateException("Failed to delete parent comment");
        }

        removedIds.forEach(contentLikeService::removeComment);
        // 计数不能被删除操作压到负数。
        boolean counted = blogService.update()
                .setSql("comments = GREATEST(IFNULL(comments, 0) - " + removed + ", 0)")
                .eq("id", comment.getBlogId())
                .update();
        if (!counted) {
            throw new IllegalStateException("Failed to decrement blog comment count");
        }
        return Result.ok();
    }

    /** Public views retain the existing author exception; admin review uses its separate endpoint. */
    private boolean canRead(BlogComments comment, Long viewerId) {
        return comment != null && (!CommentStatus.isBlocked(comment.getStatus())
                || (viewerId != null && Objects.equals(comment.getUserId(), viewerId)));
    }

    /** At most one lookup for the bounded page/preview's referenced comments. */
    private Map<Long, BlogComments> loadVisibleAnswers(List<BlogComments> replies, Long viewerId) {
        Set<Long> ids = replies.stream().map(BlogComments::getAnswerId)
                .filter(id -> id != null && id > 0).collect(Collectors.toSet());
        if (ids.isEmpty()) { return Collections.emptyMap(); }
        Map<Long, BlogComments> answers = new LinkedHashMap<>();
        for (BlogComments answer : listByIds(ids)) {
            if (canRead(answer, viewerId)) { answers.put(answer.getId(), answer); }
        }
        return answers;
    }

    private void decorateAnswer(BlogCommentDTO dto, BlogComments reply,
                                Map<Long, BlogComments> answers, Map<Long, User> users) {
        Long answerId = reply.getAnswerId();
        if (answerId == null || answerId <= 0) { return; }
        BlogComments answer = answers.get(answerId);
        if (answer != null && Objects.equals(answer.getBlogId(), reply.getBlogId())
                && (Objects.equals(answer.getId(), reply.getParentId())
                || Objects.equals(answer.getParentId(), reply.getParentId()))) {
            dto.setAnswerNickName(nickNameOf(answer.getUserId(), users));
        } else {
            dto.setAnswerId(0L);
            dto.setAnswerNickName("评论不可见或已删除");
        }
    }

    private long normalizeId(Long value) {
        return value == null || value < 0 ? ROOT_PARENT_ID : value;
    }

    private Map<Long, User> loadUsers(List<BlogComments> roots, List<BlogComments> replies) {
        Set<Long> userIds = new HashSet<>();
        roots.forEach(item -> {
            if (item.getUserId() != null) {
                userIds.add(item.getUserId());
            }
        });
        replies.forEach(item -> {
            if (item.getUserId() != null) {
                userIds.add(item.getUserId());
            }
        });
        if (userIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<User> users = userService.listByIds(userIds);
        if (users == null || users.isEmpty()) {
            return Collections.emptyMap();
        }
        return users.stream().collect(Collectors.toMap(User::getId, Function.identity(), (left, right) -> left));
    }

    private BlogCommentDTO toDTO(BlogComments comment, Map<Long, User> userIndex) {
        User author = comment.getUserId() == null ? null : userIndex.get(comment.getUserId());
        return new BlogCommentDTO()
                .setId(comment.getId())
                .setBlogId(comment.getBlogId())
                .setUserId(comment.getUserId())
                .setNickName(author == null ? DELETED_USER_NAME : author.getNickName())
                .setIcon(author == null ? "" : author.getIcon())
                .setContent(comment.getContent())
                .setParentId(comment.getParentId())
                .setAnswerId(comment.getAnswerId())
                .setCreateTime(comment.getCreateTime())
                .setLiked(likedOf(comment))
                .setIsLike(Boolean.FALSE)
                .setStatus(CommentStatus.normalize(comment.getStatus()));
    }

    /**
     * 当前登录用户 id；游客返回 null。
     */
    private Long currentUserId() {
        UserDTO loginUser = UserHolder.getUser();
        return loginUser == null ? null : loginUser.getId();
    }

    /**
     * 可见性规则：被屏蔽的评论对其他人隐藏，但作者本人仍能看到自己的内容，
     * 避免用户以为评论凭空消失。
     */
    private QueryWrapper<BlogComments> applyVisibility(QueryWrapper<BlogComments> wrapper, Long viewerId) {
        wrapper.ne("status", CommentStatus.BLOCKED);
        if (viewerId != null) {
            wrapper.or(inner -> inner.eq("status", CommentStatus.BLOCKED).eq("user_id", viewerId));
        }
        return wrapper;
    }

    /**
     * 对未填充的点赞数使用 0。
     */
    private Integer likedOf(BlogComments comment) {
        Integer liked = comment.getLiked();
        return liked == null ? Integer.valueOf(0) : liked;
    }

    private String nickNameOf(Long userId, Map<Long, User> userIndex) {
        User user = userId == null ? null : userIndex.get(userId);
        return user == null ? DELETED_USER_NAME : user.getNickName();
    }
}
