package com.yuelin.controller;

import com.yuelin.dto.Result;
import com.yuelin.service.IBlogCommentReportService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.Map;

/**
 * 评论举报与管理员审核接口。
 *
 * <p>举报入口对已登录用户开放；{@code /admin} 下的接口由 AdminInterceptor 限制为配置的管理员。</p>
 */
@RestController
@RequestMapping("/blog-comment-report")
public class BlogCommentReportController {

    @Resource
    private IBlogCommentReportService blogCommentReportService;

    /**
     * 举报一条评论，需要登录。
     */
    @PostMapping("/{commentId}")
    public Result reportComment(@PathVariable("commentId") Long commentId,
                                @RequestBody(required = false) Map<String, String> body) {
        String reason = body == null ? null : body.get("reason");
        return blogCommentReportService.reportComment(commentId, reason);
    }

    /**
     * 管理员查询待处理举报。
     */
    @GetMapping("/admin/pending")
    public Result queryPendingReports(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        return blogCommentReportService.queryPendingReports(current);
    }

    /**
     * 管理员屏蔽评论。
     */
    @PutMapping("/admin/block/{commentId}")
    public Result blockComment(@PathVariable("commentId") Long commentId) {
        return blogCommentReportService.blockComment(commentId);
    }

    /**
     * 管理员恢复评论。
     */
    @PutMapping("/admin/restore/{commentId}")
    public Result restoreComment(@PathVariable("commentId") Long commentId) {
        return blogCommentReportService.restoreComment(commentId);
    }
    @GetMapping("/admin/history")
    public Result history(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        return blogCommentReportService.queryHistory(current);
    }

    @PutMapping("/admin/dismiss/{commentId}")
    public Result dismiss(@PathVariable Long commentId) { return blogCommentReportService.dismissReports(commentId); }
}
