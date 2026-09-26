package com.yuelin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yuelin.dto.CommentReportDTO;
import com.yuelin.dto.Result;
import com.yuelin.dto.UserDTO;
import com.yuelin.entity.BlogCommentReport;
import com.yuelin.entity.BlogComments;
import com.yuelin.mapper.BlogCommentReportMapper;
import com.yuelin.service.IBlogCommentReportService;
import com.yuelin.service.IBlogCommentsService;
import com.yuelin.service.CommentMutationGuard;
import org.springframework.jdbc.core.JdbcTemplate;
import com.yuelin.utils.CommentStatus;
import com.yuelin.utils.SystemConstants;
import com.yuelin.utils.UserHolder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 评论举报与审核实现。
 *
 * <p>举报本身不会隐藏内容，只把评论标记为“被举报”并生成待办；是否屏蔽由管理员决定。
 * 这样可以避免刷举报即时压制正常言论。</p>
 */
@Service
public class BlogCommentReportServiceImpl
        extends ServiceImpl<BlogCommentReportMapper, BlogCommentReport>
        implements IBlogCommentReportService {

    /**
     * 与 tb_blog_comment_report.reason 的 varchar(255) 保持一致。
     */
    static final int MAX_REASON_LENGTH = 255;

    @Resource
    private IBlogCommentsService blogCommentsService;

    @Resource private CommentMutationGuard mutationGuard;
    @Resource private JdbcTemplate jdbcTemplate;

    @Override
    @Transactional
    public Result reportComment(Long commentId, String reason) {
        if (commentId == null || commentId <= 0) {
            return Result.fail("评论ID无效");
        }
        UserDTO loginUser = UserHolder.getUser();
        if (loginUser == null || loginUser.getId() == null) {
            return Result.fail("请先登录");
        }
        String trimmedReason = reason == null ? "" : reason.trim();
        if (trimmedReason.isEmpty()) {
            return Result.fail("请填写举报理由");
        }
        if (trimmedReason.length() > MAX_REASON_LENGTH) {
            return Result.fail("举报理由不能超过" + MAX_REASON_LENGTH + "字");
        }

        BlogComments comment = blogCommentsService.getById(commentId);
        if (comment == null) {
            return Result.fail("评论不存在或已被删除");
        }
        Long reporterId = loginUser.getId();
        if (Objects.equals(comment.getUserId(), reporterId)) {
            return Result.fail("不能举报自己的评论");
        }
        if (CommentStatus.isBlocked(comment.getStatus())) {
            return Result.fail("该评论已被屏蔽，无需重复举报");
        }
        if (count(new QueryWrapper<BlogCommentReport>()
                .eq("comment_id", commentId)
                .eq("user_id", reporterId)) > 0) {
            return Result.fail("你已经举报过这条评论");
        }

        if (!mutationGuard.lockBlog(comment.getBlogId())) { return Result.fail("评论不存在或不可见"); }
        Long rootId = Objects.equals(comment.getParentId(), 0L) ? commentId : comment.getParentId();
        BlogComments root = mutationGuard.lockComment(rootId);
        BlogComments current = Objects.equals(rootId, commentId) ? root : mutationGuard.lockComment(commentId);
        if (root == null || current == null || !Objects.equals(root.getParentId(), 0L)
                || !Objects.equals(root.getBlogId(), current.getBlogId())
                || (CommentStatus.isBlocked(root.getStatus()) && !Objects.equals(root.getUserId(), reporterId))
                || CommentStatus.isBlocked(current.getStatus())) { return Result.fail("评论不存在或不可见"); }

        BlogCommentReport report = new BlogCommentReport()
                .setCommentId(commentId)
                .setUserId(reporterId)
                .setReason(trimmedReason)
                .setHandled(Boolean.FALSE)
                .setCreateTime(LocalDateTime.now())
                .setUpdateTime(LocalDateTime.now());
        try {
            if (!save(report)) {
                return Result.fail("举报失败，请稍后重试");
            }
        } catch (DuplicateKeyException concurrentDuplicate) {
            // 唯一索引兜住并发重复举报，此时视为已举报成功。
            return Result.fail("你已经举报过这条评论");
        }

        if (CommentStatus.normalize(current.getStatus()) == CommentStatus.NORMAL
                && jdbcTemplate.update("UPDATE tb_blog_comments SET status=1 WHERE id=?", commentId) != 1) {
            throw new IllegalStateException("Failed to mark reported comment");
        }
        return Result.ok();
    }

    @Override
    public Result queryPendingReports(Integer current) {
        int pageNo = current == null || current < 1 ? 1 : current;
        Page<BlogCommentReport> page = query()
                .eq("handled", Boolean.FALSE)
                .orderByAsc("create_time")
                .orderByAsc("id")
                .page(new Page<>(pageNo, SystemConstants.MAX_PAGE_SIZE));

        List<BlogCommentReport> records = page.getRecords();
        if (records == null || records.isEmpty()) {
            return Result.ok(Collections.emptyList(), page.getTotal());
        }

        List<Long> commentIds = records.stream()
                .map(BlogCommentReport::getCommentId)
                .distinct()
                .collect(Collectors.toList());
        Map<Long, BlogComments> commentIndex = new HashMap<>();
        List<BlogComments> comments = blogCommentsService.listByIds(commentIds);
        if (comments != null) {
            comments.forEach(comment -> commentIndex.put(comment.getId(), comment));
        }
        Map<Long, Long> reportCounts = countReportsByComment(commentIds);

        List<CommentReportDTO> result = new ArrayList<>(records.size());
        for (BlogCommentReport report : records) {
            BlogComments comment = commentIndex.get(report.getCommentId());
            result.add(new CommentReportDTO()
                    .setId(report.getId())
                    .setCommentId(report.getCommentId())
                    .setUserId(report.getUserId())
                    .setReason(report.getReason())
                    .setCreateTime(report.getCreateTime())
                    .setCommentContent(comment == null ? null : comment.getContent())
                    .setCommentUserId(comment == null ? null : comment.getUserId())
                    .setCommentStatus(comment == null ? null : CommentStatus.normalize(comment.getStatus()))
                    .setBlogId(comment == null ? null : comment.getBlogId())
                    .setReportCount(reportCounts.getOrDefault(report.getCommentId(), 0L)));
        }
        return Result.ok(result, page.getTotal());
    }

    @Override
    @Transactional
    public Result blockComment(Long commentId) {
        return moderate(commentId, CommentStatus.BLOCKED);
    }

    @Override
    @Transactional
    public Result restoreComment(Long commentId) {
        return moderate(commentId, CommentStatus.NORMAL);
    }

    /**
     * 管理员处理入口。无论目标状态是屏蔽还是恢复，都要把相关举报标记为已处理，
     * 否则同一条评论会永远停留在待办列表里。
     */
    private Result moderate(Long commentId, int targetStatus) {
        if (commentId == null || commentId <= 0) {
            return Result.fail("评论ID无效");
        }
        BlogComments comment = blogCommentsService.getById(commentId);
        if (comment == null) {
            return Result.fail("评论不存在或已被删除");
        }
        if (!mutationGuard.lockBlog(comment.getBlogId())) { return Result.fail("评论不存在或已被删除"); }
        comment = mutationGuard.lockComment(commentId);
        if (comment == null) { return Result.fail("评论不存在或已被删除"); }
        Long actor = actor();
        int previous = CommentStatus.normalize(comment.getStatus());
        if (previous != targetStatus && jdbcTemplate.update("UPDATE tb_blog_comments SET status=? WHERE id=?", targetStatus, commentId) != 1) {
            throw new IllegalStateException("Moderation write failed");
        }
        jdbcTemplate.update("UPDATE tb_blog_comment_report SET handled=1,update_time=NOW() WHERE comment_id=? AND handled=0", commentId);
        jdbcTemplate.update("INSERT INTO tb_comment_moderation_audit (comment_id,actor_id,action,previous_status,next_status) VALUES (?,?,?,?,?)",
                commentId, actor, targetStatus == CommentStatus.BLOCKED ? "BLOCK" : "RESTORE", previous, targetStatus);
        return Result.ok();
    }

    @Override
    @Transactional
    public Result dismissReports(Long commentId) {
        if (commentId == null || commentId <= 0) { return Result.fail("评论ID无效"); }
        Long actor = actor();
        // Resolve obsolete/deleted targets without deleting historical evidence.
        if (jdbcTemplate.update("UPDATE tb_blog_comment_report SET handled=1,update_time=NOW() WHERE comment_id=? AND handled=0", commentId) < 1) {
            return Result.fail("没有待处理举报");
        }
        jdbcTemplate.update("INSERT INTO tb_comment_moderation_audit (comment_id,actor_id,action) VALUES (?,?,'DISMISS')", commentId, actor);
        return Result.ok();
    }

    @Override
    public Result queryHistory(Integer current) {
        long page = current == null || current < 1 ? 1L : current.longValue();
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tb_comment_moderation_audit", Long.class);
        return Result.ok(jdbcTemplate.queryForList("SELECT CAST(id AS CHAR) AS id, CAST(comment_id AS CHAR) AS comment_id, " +
                "CAST(actor_id AS CHAR) AS actor_id, action, previous_status, next_status, create_time " +
                "FROM tb_comment_moderation_audit ORDER BY id DESC LIMIT 10 OFFSET ?", (page - 1) * 10), count);
    }

    private Long actor() {
        UserDTO user = UserHolder.getUser();
        if (user == null || user.getId() == null) { throw new IllegalArgumentException("请先登录"); }
        return user.getId();
    }

    /**
     * 统计这批评论各自被举报的总次数，用于管理员判断严重程度。
     */
    private Map<Long, Long> countReportsByComment(List<Long> commentIds) {
        if (commentIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, Long> counts = new HashMap<>();
        // IDs are bounded by the report page; aggregate in SQL rather than loading every report.
        for (Long id : commentIds) {
            counts.put(id, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tb_blog_comment_report WHERE comment_id=?", Long.class, id));
        }
        return counts;
    }
}
