package com.yuelin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 评论举报记录。同一用户对同一评论只能举报一次，由唯一索引保证。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_blog_comment_report")
public class BlogCommentReport implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 被举报的评论id
     */
    private Long commentId;

    /**
     * 举报人id
     */
    private Long userId;

    /**
     * 举报理由
     */
    private String reason;

    /**
     * 是否已被管理员处理
     */
    private Boolean handled;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;
}
