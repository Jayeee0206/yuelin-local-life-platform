package com.yuelin.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 管理员审核列表中的举报条目。
 */
@Data
@Accessors(chain = true)
public class CommentReportDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private Long commentId;

    /**
     * 举报人 id。
     */
    private Long userId;

    private String reason;

    private LocalDateTime createTime;

    /**
     * 被举报评论的内容。评论已被删除时为空。
     */
    private String commentContent;

    /**
     * 被举报评论的作者 id。
     */
    private Long commentUserId;

    /**
     * 被举报评论当前状态，取值见 CommentStatus。
     */
    private Integer commentStatus;

    /**
     * 被举报评论所属笔记 id，便于管理员跳转查看上下文。
     */
    private Long blogId;

    /**
     * 该评论累计被举报次数。
     */
    private Long reportCount;
}
