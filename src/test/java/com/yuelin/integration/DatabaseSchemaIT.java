package com.yuelin.integration;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 针对真实 MySQL 的数据库集成测试。
 *
 * <p>本类验证的是<strong>单元测试无法覆盖</strong>的部分：初始建库脚本能否真正执行、
 * 表结构是否符合代码假设、以及可见性过滤在真实 SQL 引擎下的实际行为。
 *
 * <p>默认跳过。仅当环境变量 {@code INTEGRATION_DB=true} 时运行，
 * 由 GitHub Actions 的 service container 提供数据库，不占用开发机资源。
 */
@EnabledIfEnvironmentVariable(named = "INTEGRATION_DB", matches = "true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("数据库结构与可见性过滤（真实 MySQL）")
class DatabaseSchemaIT {

    private static final int NORMAL = 0;
    private static final int REPORTED = 1;
    private static final int BLOCKED = 2;

    private static String url;
    private static String user;
    private static String password;

    @BeforeAll
    static void initSchema() throws Exception {
        assertEquals("true", System.getenv("INTEGRATION_DB_ALLOW_RESET"),
                "Use an isolated disposable database and explicitly allow schema reset");
        url = env("DB_URL", "jdbc:mysql://127.0.0.1:3306/yuelin_local_life"
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai"
                + "&allowMultiQueries=true");
        user = env("DB_USERNAME", "root");
        password = env("DB_PASSWORD", "root");

        try (Connection conn = open()) {
            // 与 Compose 首次建库使用同一份脚本。
            runScript(conn, Paths.get("src/main/resources/db/yuelin_local_life.sql"));
        }
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isEmpty() ? fallback : value;
    }

    private static Connection open() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }

    /**
     * 逐条执行脚本。按分号切分，跳过注释与空语句。
     */
    private static void runScript(Connection conn, Path file) throws IOException, SQLException {
        String sql = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : sql.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("--")) {
                continue;
            }
            current.append(line).append('\n');
            if (trimmed.endsWith(";")) {
                statements.add(current.toString());
                current.setLength(0);
            }
        }
        if (current.toString().trim().length() > 0) {
            statements.add(current.toString());
        }
        try (Statement st = conn.createStatement()) {
            for (String statement : statements) {
                String body = statement.trim();
                if (body.endsWith(";")) {
                    body = body.substring(0, body.length() - 1);
                }
                if (!body.isEmpty()) {
                    st.execute(body);
                }
            }
        }
    }

    @Test
    @Order(1)
    @DisplayName("首次建库包含完整业务结构")
    void initialSchemaIsComplete() throws Exception {
        assertTrue(tableExists("tb_blog_comments"), "评论表应存在");
        assertTrue(tableExists("tb_content_like"), "点赞关系表应存在");
        assertTrue(tableExists("tb_upload_asset"), "图片归属表应存在");
        assertTrue(tableExists("tb_order_resolution"), "订单处理表应存在");
        assertTrue(indexExists("tb_blog_comments", "idx_blog_comment_blog_status"), "评论状态索引应存在");
        assertTrue(indexExists("tb_voucher_order", "uk_voucher_order_user_voucher"), "秒杀唯一索引应存在");
    }

    @Test
    @Order(2)
    @DisplayName("举报表唯一索引生效")
    void reportTableExistsWithUniqueIndex() throws Exception {
        assertTrue(tableExists("tb_blog_comment_report"),
                "tb_blog_comment_report 应由首次建库脚本创建");

        try (Connection conn = open(); Statement st = conn.createStatement()) {
            st.execute("DELETE FROM tb_blog_comment_report");
            st.execute("INSERT INTO tb_blog_comment_report (comment_id, user_id, reason) "
                    + "VALUES (9001, 7001, '测试举报')");
            boolean duplicateRejected = false;
            try {
                st.execute("INSERT INTO tb_blog_comment_report (comment_id, user_id, reason) "
                        + "VALUES (9001, 7001, '重复举报')");
            } catch (SQLException expected) {
                duplicateRejected = true;
            }
            assertTrue(duplicateRejected,
                    "同一用户对同一评论重复举报应被唯一索引拒绝");
            st.execute("DELETE FROM tb_blog_comment_report");
        }
    }

    @Test
    @Order(3)
    @DisplayName("status 列可持久化 BLOCKED=2")
    void statusColumnStoresBlocked() throws Exception {
        try (Connection conn = open(); Statement st = conn.createStatement()) {
            st.execute("DELETE FROM tb_blog_comments WHERE blog_id = 8001");
            st.execute("INSERT INTO tb_blog_comments "
                    + "(blog_id, user_id, parent_id, answer_id, content, liked, status) "
                    + "VALUES (8001, 1, 0, 0, '待屏蔽评论', 0, " + BLOCKED + ")");

            try (ResultSet rs = st.executeQuery(
                    "SELECT status FROM tb_blog_comments WHERE blog_id = 8001")) {
                assertTrue(rs.next(), "应查到刚插入的评论");
                assertEquals(BLOCKED, rs.getInt("status"),
                        "tinyint(1) 会把 2 截断或拒绝；必须能原样存回 2");
            }
            st.execute("DELETE FROM tb_blog_comments WHERE blog_id = 8001");
        }
    }

    @Test
    @Order(4)
    @DisplayName("可见性过滤：被屏蔽评论对他人隐藏、对作者可见（SEC-01）")
    void visibilityFilterBehavesCorrectly() throws Exception {
        long blogId = 8002L;
        long authorId = 501L;
        long otherId = 502L;

        try (Connection conn = open(); Statement st = conn.createStatement()) {
            st.execute("DELETE FROM tb_blog_comments WHERE blog_id IN (" + blogId + ", " + (blogId + 1) + ")");
            // 目标笔记下：正常、被举报（仍可见）、被屏蔽（受限）
            st.execute("INSERT INTO tb_blog_comments "
                    + "(blog_id, user_id, parent_id, answer_id, content, liked, status) VALUES "
                    + "(" + blogId + ", " + authorId + ", 0, 0, '正常评论', 0, " + NORMAL + "),"
                    + "(" + blogId + ", " + authorId + ", 0, 0, '被举报评论', 0, " + REPORTED + "),"
                    + "(" + blogId + ", " + authorId + ", 0, 0, '被屏蔽评论', 0, " + BLOCKED + ")");
            // 另一篇笔记下的被屏蔽评论：用于检测括号错误导致的跨笔记泄露
            st.execute("INSERT INTO tb_blog_comments "
                    + "(blog_id, user_id, parent_id, answer_id, content, liked, status) VALUES "
                    + "(" + (blogId + 1) + ", " + authorId + ", 0, 0, '其他笔记被屏蔽评论', 0, " + BLOCKED + ")");
        }

        // 与 BlogCommentsServiceImpl.applyVisibility 生成的结构保持一致
        String base = "SELECT content FROM tb_blog_comments WHERE blog_id = ? AND parent_id = 0 AND ";
        String anonymous = base + "(status <> ?)";
        String loggedIn = base + "(status <> ? OR (status = ? AND user_id = ?))";

        List<String> guestView = query(anonymous, ps -> {
            ps.setLong(1, blogId);
            ps.setInt(2, BLOCKED);
        });
        assertEquals(2, guestView.size(), "访客应只看到正常与被举报评论，实际: " + guestView);
        assertFalse(guestView.contains("被屏蔽评论"), "被屏蔽评论不得对访客可见");

        List<String> authorView = query(loggedIn, ps -> {
            ps.setLong(1, blogId);
            ps.setInt(2, BLOCKED);
            ps.setInt(3, BLOCKED);
            ps.setLong(4, authorId);
        });
        assertEquals(3, authorView.size(), "作者应能看到自己被屏蔽的评论，实际: " + authorView);
        assertTrue(authorView.contains("被屏蔽评论"), "作者应看到自己被屏蔽的评论");

        List<String> otherView = query(loggedIn, ps -> {
            ps.setLong(1, blogId);
            ps.setInt(2, BLOCKED);
            ps.setInt(3, BLOCKED);
            ps.setLong(4, otherId);
        });
        assertEquals(2, otherView.size(), "他人不应看到被屏蔽评论，实际: " + otherView);

        // 核心防泄露断言：任何视角都不得跨笔记取到内容
        for (List<String> view : List.of(guestView, authorView, otherView)) {
            assertFalse(view.contains("其他笔记被屏蔽评论"),
                    "可见性条件未被正确括起来时，OR 会绕过 blog_id 约束造成跨笔记数据泄露");
        }

        try (Connection conn = open(); Statement st = conn.createStatement()) {
            st.execute("DELETE FROM tb_blog_comments WHERE blog_id IN (" + blogId + ", " + (blogId + 1) + ")");
        }
    }

    @Test
    @Order(5)
    @DisplayName("秒杀订单唯一索引阻止重复下单")
    void voucherOrderUniqueIndexWorks() throws Exception {
        try (Connection conn = open(); Statement st = conn.createStatement()) {
            st.execute("DELETE FROM tb_voucher_order WHERE user_id = 6001");
            st.execute("INSERT INTO tb_voucher_order (id, user_id, voucher_id, pay_type, status) "
                    + "VALUES (700001, 6001, 3001, 1, 1)");
            boolean rejected = false;
            try {
                st.execute("INSERT INTO tb_voucher_order (id, user_id, voucher_id, pay_type, status) "
                        + "VALUES (700002, 6001, 3001, 1, 1)");
            } catch (SQLException expected) {
                rejected = true;
            }
            assertTrue(rejected, "同一用户对同一优惠券重复下单应被唯一索引拒绝（防超卖最后一道防线）");
            st.execute("DELETE FROM tb_voucher_order WHERE user_id = 6001");
        }
    }

    private boolean indexExists(String table, String index) throws SQLException {
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM information_schema.statistics "
                             + "WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?")) {
            ps.setString(1, table);
            ps.setString(2, index);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }

    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private List<String> query(String sql, Binder binder) throws SQLException {
        List<String> result = new ArrayList<>();
        try (Connection conn = open(); PreparedStatement ps = conn.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(rs.getString(1));
                }
            }
        }
        return result;
    }

    private boolean tableExists(String table) throws SQLException {
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM information_schema.tables "
                             + "WHERE table_schema = DATABASE() AND table_name = ?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }
}
