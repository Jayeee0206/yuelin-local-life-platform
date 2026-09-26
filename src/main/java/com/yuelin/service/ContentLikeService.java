package com.yuelin.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;

/** MySQL owns content likes and their counters. */
@Service
public class ContentLikeService {
    public enum Target { BLOG, COMMENT }
    @Resource
    private JdbcTemplate jdbcTemplate;

    public static final class State {
        private final int liked;
        private final boolean like;
        public State(int liked, boolean like) { this.liked = liked; this.like = like; }
        public int getLiked() { return liked; }
        public boolean isLike() { return like; }
    }

    @Transactional
    public State toggle(Target target, Long id, Long userId) {
        validate(target, id);
        if (userId == null || userId <= 0) { throw new IllegalArgumentException("用户ID无效"); }
        Integer count = prepare(target, id);
        if (count == null) { return null; }
        boolean wasLiked = contains(target, id, userId);
        if (wasLiked) {
            requireOne(jdbcTemplate.update("DELETE FROM tb_content_like WHERE target_type=? AND target_id=? AND user_id=?",
                    target.name(), id, userId));
        } else {
            requireOne(jdbcTemplate.update("INSERT INTO tb_content_like (target_type,target_id,user_id,liked_at) VALUES (?,?,?,?)",
                    target.name(), id, userId, System.currentTimeMillis()));
        }
        int next = Math.addExact(count, wasLiked ? -1 : 1);
        updateCount(target, id, next);
        return new State(next, !wasLiked);
    }

    @Transactional
    public State state(Target target, Long id, Long userId) {
        validate(target, id);
        Integer count = prepare(target, id);
        return count == null ? null : new State(count, userId != null && contains(target, id, userId));
    }

    @Transactional
    public List<Long> firstLikers(Long id) {
        validate(Target.BLOG, id);
        if (prepare(Target.BLOG, id) == null) { return Collections.emptyList(); }
        return jdbcTemplate.queryForList("SELECT user_id FROM tb_content_like WHERE target_type='BLOG' AND target_id=? "
                + "ORDER BY liked_at,user_id LIMIT 5 FOR UPDATE", Long.class, id);
    }

    /** Called within the parent comment deletion transaction. */
    @Transactional
    public void removeComment(Long id) {
        jdbcTemplate.update("DELETE FROM tb_content_like WHERE target_type='COMMENT' AND target_id=?", id);
    }

    private Integer prepare(Target target, Long id) {
        // Lock the target so toggles and deletion cannot race the counter update.
        List<Integer> counts = target == Target.BLOG
                ? jdbcTemplate.queryForList("SELECT COALESCE(liked,0) FROM tb_blog WHERE id=? FOR UPDATE", Integer.class, id)
                : jdbcTemplate.queryForList("SELECT COALESCE(liked,0) FROM tb_blog_comments WHERE id=? FOR UPDATE", Integer.class, id);
        return counts.isEmpty() ? null : counts.get(0);
    }

    private boolean contains(Target target, Long id, Long userId) {
        return !jdbcTemplate.queryForList("SELECT user_id FROM tb_content_like WHERE target_type=? AND target_id=? AND user_id=? FOR UPDATE",
                Long.class, target.name(), id, userId).isEmpty();
    }

    private void updateCount(Target target, Long id, int count) {
        if (count < 0) { throw new IllegalStateException("Invalid like count"); }
        int affected = target == Target.BLOG
                ? jdbcTemplate.update("UPDATE tb_blog SET liked=? WHERE id=?", count, id)
                : jdbcTemplate.update("UPDATE tb_blog_comments SET liked=? WHERE id=?", count, id);
        requireOne(affected);
    }

    private void validate(Target target, Long id) {
        if (target == null || id == null || id <= 0) { throw new IllegalArgumentException("内容ID无效"); }
    }

    private void requireOne(int affected) {
        if (affected != 1) { throw new IllegalStateException("Like state persistence failed"); }
    }
}
