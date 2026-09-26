package com.yuelin.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.yuelin.dto.BlogCommentSaveDTO;
import com.yuelin.dto.Result;
import com.yuelin.entity.BlogComments;

/**
 * <p>
 * 探店笔记评论服务。
 * </p>
 */
public interface IBlogCommentsService extends IService<BlogComments> {

    /**
     * 分页查询某条笔记的一级评论，并附带每条一级评论的最新回复。
     *
     * @param blogId  笔记 id
     * @param current 页码，从 1 开始
     * @return 评论分页结果，total 为一级评论总数
     */
    Result queryCommentsByBlogId(Long blogId, Integer current);

    /**
     * 发表评论或回复。同时维护 tb_blog.comments 计数。
     *
     * @param saveDTO 评论请求体
     * @return 新评论的展示对象
     */
    Result saveComment(BlogCommentSaveDTO saveDTO);

    /**
     * 删除本人发表的评论。一级评论会连同其回复一起删除。
     *
     * @param id 评论 id
     * @return 操作结果
     */
    Result deleteComment(Long id);

    /**
     * 分页查询某条一级评论下的全部回复。
     *
     * @param parentId 一级评论 id
     * @param current  页码，从 1 开始
     * @return 回复分页结果，total 为该楼回复总数
     */
    Result queryRepliesByParentId(Long parentId, Integer current);

    /**
     * 切换评论点赞状态。同一用户重复调用会在点赞与取消之间切换。
     *
     * @param id 评论 id
     * @return 操作后的点赞数与点赞状态
     */
    Result likeComment(Long id);
}
