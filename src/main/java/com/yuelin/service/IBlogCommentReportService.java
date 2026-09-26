package com.yuelin.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.yuelin.dto.Result;
import com.yuelin.entity.BlogCommentReport;

/**
 * 评论举报与管理员审核服务。
 */
public interface IBlogCommentReportService extends IService<BlogCommentReport> {

    /**
     * 举报一条评论。同一用户对同一评论重复举报会被拒绝。
     *
     * @param commentId 被举报的评论 id
     * @param reason    举报理由
     * @return 操作结果
     */
    Result reportComment(Long commentId, String reason);

    /**
     * 管理员分页查询待处理举报。
     *
     * @param current 页码，从 1 开始
     * @return 举报列表，含被举报评论的内容摘要
     */
    Result queryPendingReports(Integer current);

    /**
     * 管理员屏蔽一条评论，并把相关举报标记为已处理。
     *
     * @param commentId 评论 id
     * @return 操作结果
     */
    Result blockComment(Long commentId);

    /**
     * 管理员恢复一条评论为正常状态，并把相关举报标记为已处理。
     *
     * @param commentId 评论 id
     * @return 操作结果
     */
    Result restoreComment(Long commentId);

    Result dismissReports(Long commentId);

    Result queryHistory(Integer current);
}
