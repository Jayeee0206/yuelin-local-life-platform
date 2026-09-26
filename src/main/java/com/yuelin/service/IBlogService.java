package com.yuelin.service;

import com.yuelin.dto.Result;
import com.yuelin.entity.Blog;
import com.baomidou.mybatisplus.extension.service.IService;

public interface IBlogService extends IService<Blog> {

    Result queryById(Long id);

    Result queryHotBlog(Integer current);

    Result likeBlog(Long id);

    Result queryBlogLikes(Long id);

    Result saveBlog(Blog blog);

    Result queryBlogOfFollow(Long max, Integer offset);
}
