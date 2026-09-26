package com.yuelin.service;

import com.yuelin.entity.BlogComments;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.List;

/** All comment creation/deletion locks the blog first, then current comment rows. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class CommentMutationGuard {
    @Resource
    private JdbcTemplate jdbcTemplate;

    public boolean lockBlog(Long id) {
        return !jdbcTemplate.queryForList("SELECT id FROM tb_blog WHERE id=? FOR UPDATE", Long.class, id).isEmpty();
    }

    public BlogComments lockComment(Long id) {
        List<BlogComments> rows = jdbcTemplate.query("SELECT * FROM tb_blog_comments WHERE id=? FOR UPDATE",
                new BeanPropertyRowMapper<>(BlogComments.class), id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<BlogComments> lockReplies(Long parentId) {
        return jdbcTemplate.query("SELECT * FROM tb_blog_comments WHERE parent_id=? ORDER BY id FOR UPDATE",
                new BeanPropertyRowMapper<>(BlogComments.class), parentId);
    }
}
