package com.yuelin.service;

import com.yuelin.entity.Blog;
import com.yuelin.mapper.BlogMapper;
import com.yuelin.mapper.ShopMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

/** Commit a validated blog and its upload bindings in one transaction. */
@Service
public class BlogPublicationService {
    static final int MAX_TITLE_LENGTH = 255;
    static final int MAX_CONTENT_LENGTH = 2000;

    @Resource
    private BlogMapper blogMapper;
    @Resource
    private ShopMapper shopMapper;
    @Resource
    private UploadAssetService uploadAssetService;

    @Transactional
    public void publish(Blog blog, Long owner) {
        validateAndNormalize(blog, owner);
        if (blogMapper.insert(blog) != 1) {
            throw new IllegalStateException("Blog insertion failed");
        }
        String original = blog.getImages();
        blog.setImages(uploadAssetService.attach(original, owner, blog.getId()));
        if (!original.equals(blog.getImages()) && blogMapper.updateById(blog) != 1) {
            throw new IllegalStateException("Blog image binding failed");
        }
    }

    private void validateAndNormalize(Blog blog, Long owner) {
        if (blog == null || owner == null || owner <= 0) {
            throw new IllegalArgumentException("请先登录并填写笔记");
        }
        String title = trim(blog.getTitle());
        String content = trim(blog.getContent());
        String images = trim(blog.getImages());
        if (title.isEmpty()) {
            throw new IllegalArgumentException("标题不能为空");
        }
        if (title.length() > MAX_TITLE_LENGTH) {
            throw new IllegalArgumentException("标题不能超过255个字符");
        }
        if (content.isEmpty()) {
            throw new IllegalArgumentException("正文不能为空");
        }
        if (content.length() > MAX_CONTENT_LENGTH) {
            throw new IllegalArgumentException("正文不能超过2000个字符");
        }
        if (images.isEmpty()) {
            throw new IllegalArgumentException("请先上传图片");
        }
        if (blog.getShopId() == null || blog.getShopId() <= 0 || shopMapper.selectById(blog.getShopId()) == null) {
            throw new IllegalArgumentException("关联商铺不存在");
        }

        // Never trust ownership, counters, or audit fields supplied by the client.
        blog.setId(null);
        blog.setUserId(owner);
        blog.setTitle(title);
        blog.setContent(content);
        blog.setImages(images);
        blog.setLiked(0);
        blog.setComments(0);
        blog.setCreateTime(null);
        blog.setUpdateTime(null);
        blog.setIcon(null);
        blog.setName(null);
        blog.setIsLike(null);
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
