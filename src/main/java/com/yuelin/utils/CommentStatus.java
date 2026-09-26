package com.yuelin.utils;

/**
 * 评论审核状态。对应 tb_blog_comments.status 字段。
 */
public final class CommentStatus {

    /**
     * 正常，所有人可见。
     */
    public static final int NORMAL = 0;

    /**
     * 已被举报，等待管理员处理。此状态下评论仍然可见，避免恶意举报即时屏蔽内容。
     */
    public static final int REPORTED = 1;

    /**
     * 已被管理员屏蔽，仅作者本人和管理员可见。
     */
    public static final int BLOCKED = 2;

    private CommentStatus() {
    }

    /**
     * 对缺失的状态使用正常状态。
     */
    public static int normalize(Integer status) {
        return status == null ? NORMAL : status;
    }

    /**
     * 是否已被屏蔽。
     */
    public static boolean isBlocked(Integer status) {
        return normalize(status) == BLOCKED;
    }

    /**
     * 是否为合法的状态取值。
     */
    public static boolean isValid(int status) {
        return status == NORMAL || status == REPORTED || status == BLOCKED;
    }
}
