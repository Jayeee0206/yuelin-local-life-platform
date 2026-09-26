package com.yuelin.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 发表评论的请求体。
 */
@Data
public class BlogCommentSaveDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 被评论的探店笔记 id。
     */
    private Long blogId;

    /**
     * 评论内容，服务端会再做一次长度和空白校验。
     */
    private String content;

    /**
     * 所属一级评论 id；为空或 0 表示这是一条一级评论。
     */
    private Long parentId;

    /**
     * 被回复的评论 id；为空或 0 表示直接回复一级评论。
     */
    private Long answerId;
}
