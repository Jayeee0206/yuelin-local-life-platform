package com.yuelin.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 探店笔记评论的对外视图。只暴露展示所需字段，不直接返回数据库实体。
 */
@Data
@Accessors(chain = true)
public class BlogCommentDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private Long blogId;

    private Long userId;

    /**
     * 评论作者昵称。用户已注销时为占位文案。
     */
    private String nickName;

    /**
     * 评论作者头像。
     */
    private String icon;

    private String content;

    /**
     * 一级评论为 0，楼中楼为所属一级评论 id。
     */
    private Long parentId;

    /**
     * 被回复的评论 id，0 表示直接回复一级评论。
     */
    private Long answerId;

    /**
     * 被回复者昵称，便于前端展示“回复 xxx”。
     */
    private String answerNickName;

    private LocalDateTime createTime;

    /**
     * 点赞数。
     */
    private Integer liked = 0;

    /**
     * 当前登录用户是否已点赞；游客恒为 false。
     */
    private Boolean isLike = Boolean.FALSE;

    /**
     * 审核状态，取值见 CommentStatus。被屏蔽的评论只会出现在作者本人的视图中。
     */
    private Integer status = 0;

    /**
     * 该一级评论下的回复总数。
     */
    private Integer replyCount = 0;

    /**
     * 该一级评论下的回复列表，楼中楼自身该字段为空列表。
     * 评论的身份由 id 决定，回复列表只是装载结果，因此不参与 equals/hashCode。
     */
    @EqualsAndHashCode.Exclude
    private List<BlogCommentDTO> replies = new ArrayList<>();

    /**
     * 返回回复列表的副本，避免调用方直接改写 DTO 内部集合。
     */
    public List<BlogCommentDTO> getReplies() {
        return new ArrayList<>(this.replies);
    }

    /**
     * 以副本方式保存回复列表，避免外部集合与 DTO 共享引用。
     */
    public BlogCommentDTO setReplies(List<BlogCommentDTO> replies) {
        this.replies = replies == null ? new ArrayList<>() : new ArrayList<>(replies);
        return this;
    }

    /**
     * 追加一条回复。列表由 DTO 自己维护，调用方不需要持有内部引用。
     */
    public BlogCommentDTO addReply(BlogCommentDTO reply) {
        if (reply != null) {
            this.replies.add(reply);
        }
        return this;
    }

    /**
     * 当前已装载的回复条数。
     */
    public int loadedReplyCount() {
        return this.replies.size();
    }
}
