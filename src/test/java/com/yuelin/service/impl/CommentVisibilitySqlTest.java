package com.yuelin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.yuelin.entity.BlogComments;
import com.yuelin.utils.CommentStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 评论可见性过滤的 SQL 结构验证。
 *
 * <p>业务规则：被管理员屏蔽（status = 2）的评论对其他人隐藏，但作者本人仍可见。
 * 该规则由 {@code applyVisibility} 拼接到查询条件中，形如：
 * {@code blog_id = ? AND parent_id = ? AND (可见性条件)}。
 *
 * <p>这里断言的是<strong>生成的 SQL 片段结构</strong>，而不是 mock 的返回值。
 * 之所以必须单独验证：可见性条件内部混用了 OR，如果外层没有把它整体括起来，
 * OR 会与前面的 blog_id 条件在同一优先级上结合，导致
 * {@code blog_id = 1 AND parent_id = 0 AND status != 2 OR (status = 2 AND user_id = ?) ...}
 * ——此时 OR 右侧脱离了 blog_id 约束，<strong>其他笔记下被屏蔽的评论会全部泄露</strong>。
 * 这是数据泄露级别的缺陷，且 mock 单元测试完全无法覆盖。
 */
class CommentVisibilitySqlTest {

    /**
     * 复用生产代码的私有方法，避免测试里重写一份逻辑而与实现脱节。
     */
    private String visibilitySql(Long viewerId) throws Exception {
        Method method = BlogCommentsServiceImpl.class
                .getDeclaredMethod("applyVisibility", QueryWrapper.class, Long.class);
        method.setAccessible(true);
        BlogCommentsServiceImpl service = new BlogCommentsServiceImpl();
        QueryWrapper<BlogComments> wrapper = new QueryWrapper<>();
        method.invoke(service, wrapper, viewerId);
        return wrapper.getTargetSql();
    }

    /**
     * 模拟真实调用现场：外层先按 blog_id / parent_id 过滤，再 AND 上可见性条件。
     */
    private String fullQuerySql(Long viewerId) throws Exception {
        Method method = BlogCommentsServiceImpl.class
                .getDeclaredMethod("applyVisibility", QueryWrapper.class, Long.class);
        method.setAccessible(true);
        BlogCommentsServiceImpl service = new BlogCommentsServiceImpl();

        QueryWrapper<BlogComments> outer = new QueryWrapper<>();
        outer.eq("blog_id", 1L)
                .eq("parent_id", 0L)
                .and(inner -> {
                    try {
                        method.invoke(service, inner, viewerId);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                });
        return outer.getTargetSql();
    }

    @Test
    @DisplayName("已登录用户：可见性条件被整体括起来，不会与 blog_id 条件串味")
    void loggedInVisibilityIsGrouped() throws Exception {
        String sql = fullQuerySql(42L);

        // 可见性部分必须作为一个整体被括号包裹，紧跟在 parent_id 条件之后
        assertTrue(sql.matches(".*parent_id\\s*=\\s*\\?\\s+AND\\s*\\(.*\\).*"),
                "可见性条件必须被括号整体包裹，否则 OR 会泄露其他笔记的评论。实际 SQL: " + sql);

        // OR 必须出现在括号内部，而不是顶层
        int firstParen = sql.indexOf('(');
        int orIndex = sql.indexOf(" OR ");
        assertTrue(firstParen >= 0 && orIndex > firstParen,
                "OR 必须位于括号内部。实际 SQL: " + sql);
    }

    @Test
    @DisplayName("未登录访客：不应出现 user_id 分支")
    void anonymousHasNoAuthorBranch() throws Exception {
        String sql = visibilitySql(null);
        assertFalse(sql.contains("user_id"),
                "未登录时不应产生作者豁免分支。实际 SQL: " + sql);
        assertFalse(sql.contains("status IS NULL"), "首版结构的状态列不允许 NULL。实际 SQL: " + sql);
        assertTrue(sql.contains("status <> ?") || sql.contains("status != ?"),
                "应排除被屏蔽状态。实际 SQL: " + sql);
    }

    @Test
    @DisplayName("已登录用户：作者豁免分支必须自成一组括号")
    void authorExemptionIsGrouped() throws Exception {
        String sql = visibilitySql(42L);

        // 作者豁免是 status = BLOCKED AND user_id = viewer，两个条件必须绑定在一起，
        // 否则 user_id 会与前面的 OR 错误结合，使该用户看到所有被屏蔽评论。
        assertTrue(sql.matches(".*OR\\s*\\([^()]*status\\s*=\\s*\\?[^()]*AND[^()]*user_id\\s*=\\s*\\?[^()]*\\).*"),
                "作者豁免分支必须是被括号包裹的 (status = ? AND user_id = ?)。实际 SQL: " + sql);
    }

    @Test
    @DisplayName("BLOCKED 常量参与查询，取值为 2")
    void blockedConstantIsBoundAsTwo() throws Exception {
        Method method = BlogCommentsServiceImpl.class
                .getDeclaredMethod("applyVisibility", QueryWrapper.class, Long.class);
        method.setAccessible(true);
        BlogCommentsServiceImpl service = new BlogCommentsServiceImpl();
        QueryWrapper<BlogComments> wrapper = new QueryWrapper<>();
        method.invoke(service, wrapper, 42L);
        // 参数表在 SQL 片段生成时才填充，必须先触发一次。
        wrapper.getTargetSql();

        String params = wrapper.getParamNameValuePairs().values().toString();
        assertTrue(params.contains(String.valueOf(CommentStatus.BLOCKED)),
                "查询参数中应绑定 BLOCKED=2。实际参数: " + params);
        assertTrue(params.contains("42"),
                "查询参数中应绑定 viewerId。实际参数: " + params);
    }

    @Test
    @DisplayName("登录与未登录生成不同 SQL，作者豁免不会漏加")
    void loggedInDiffersFromAnonymous() throws Exception {
        assertFalse(visibilitySql(null).equals(visibilitySql(7L)),
                "登录用户必须额外获得作者豁免分支");
    }
}
