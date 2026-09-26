package com.yuelin.controller;

import com.yuelin.dto.BlogCommentSaveDTO;
import com.yuelin.dto.Result;
import com.yuelin.service.IBlogCommentsService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

/**
 * <p>
 * 探店笔记评论前端控制器。
 * </p>
 */
@RestController
@RequestMapping("/blog-comments")
public class BlogCommentsController {

    @Resource
    private IBlogCommentsService blogCommentsService;

    /**
     * 查询某条笔记的评论，游客可见。
     */
    @GetMapping("/of/blog/{blogId}")
    public Result queryCommentsOfBlog(@PathVariable("blogId") Long blogId,
                                      @RequestParam(value = "current", defaultValue = "1") Integer current) {
        return blogCommentsService.queryCommentsByBlogId(blogId, current);
    }

    /**
     * 发表评论或回复，需要登录。
     */
    @PostMapping
    public Result saveComment(@RequestBody BlogCommentSaveDTO saveDTO) {
        return blogCommentsService.saveComment(saveDTO);
    }

    /**
     * 展开某条一级评论下的全部回复，游客可见。
     */
    @GetMapping("/replies/{parentId}")
    public Result queryReplies(@PathVariable("parentId") Long parentId,
                               @RequestParam(value = "current", defaultValue = "1") Integer current) {
        return blogCommentsService.queryRepliesByParentId(parentId, current);
    }

    /**
     * 切换评论点赞状态，需要登录。
     */
    @PutMapping("/like/{id}")
    public Result likeComment(@PathVariable("id") Long id) {
        return blogCommentsService.likeComment(id);
    }

    /**
     * 删除本人评论，需要登录。
     */
    @DeleteMapping("/{id}")
    public Result deleteComment(@PathVariable("id") Long id) {
        return blogCommentsService.deleteComment(id);
    }
}
