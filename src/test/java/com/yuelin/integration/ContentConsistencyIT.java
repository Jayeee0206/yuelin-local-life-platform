package com.yuelin.integration;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.yuelin.dto.BlogCommentSaveDTO;
import com.yuelin.dto.BlogCommentDTO;
import com.yuelin.dto.Result;
import com.yuelin.entity.BlogComments;
import com.yuelin.entity.Blog;
import com.yuelin.entity.VoucherOrder;
import com.yuelin.service.impl.VoucherOrderServiceImpl;
import com.yuelin.service.impl.SeckillVoucherServiceImpl;
import com.yuelin.service.impl.BlogCommentReportServiceImpl;
import com.yuelin.messaging.MQSender;
import com.yuelin.utils.RedisIdWorker;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import com.yuelin.entity.User;
import com.yuelin.config.MybatisConfig;
import com.yuelin.dto.UserDTO;
import com.yuelin.entity.Shop;
import com.yuelin.service.*;
import com.yuelin.service.impl.BlogCommentsServiceImpl;
import com.yuelin.service.impl.BlogServiceImpl;
import com.yuelin.service.impl.ShopServiceImpl;
import com.yuelin.utils.CacheClient;
import com.yuelin.utils.UserHolder;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.function.IntConsumer;

import static com.yuelin.service.ContentLikeService.Target.*;
import static com.yuelin.utils.RedisConstants.CACHE_SHOP_KEY;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real MySQL transactions and concurrent service calls. */
@EnabledIfEnvironmentVariable(named = "INTEGRATION_DB", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ContentConsistencyIT {
    private static final long BLOG_ID = 900001L;
    private static final long ROOT_ID = 900011L;
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private ContentLikeService likes;
    private IBlogCommentsService comments;
    private IShopService shops;
    private ShopCacheVersionService versions;
    private StringRedisTemplate redis;
    private CacheClient cache;
    private TransactionTemplate transaction;

    @Configuration
    @EnableTransactionManagement
    @MapperScan("com.yuelin.mapper")
    static class Services {
        @Bean DataSource dataSource() {
            return new DriverManagerDataSource(System.getenv("DB_URL"), System.getenv("DB_USERNAME"), System.getenv("DB_PASSWORD"));
        }
        @Bean JdbcTemplate jdbcTemplate(DataSource dataSource) { return new JdbcTemplate(dataSource); }
        @Bean PlatformTransactionManager transactionManager(DataSource dataSource) { return new DataSourceTransactionManager(dataSource); }
        @Bean CommentReadProbe commentReadProbe() { return new CommentReadProbe(); }
        @Bean SqlSessionFactory sqlSessionFactory(DataSource dataSource, CommentReadProbe probe) throws Exception {
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            MybatisConfiguration config = new MybatisConfiguration();
            config.setMapUnderscoreToCamelCase(true);
            factory.setConfiguration(config);
            factory.setPlugins(new MybatisConfig().mybatisPlusInterceptor(), probe);
            return factory.getObject();
        }
        @Bean StringRedisTemplate stringRedisTemplate() { return mock(StringRedisTemplate.class); }
        @Bean CacheClient cacheClient() { return mock(CacheClient.class); }
        @Bean IUserService userService() { return mock(IUserService.class); }
        @Bean IFollowService followService() { return mock(IFollowService.class); }
        @Bean ContentLikeService contentLikeService() { return new ContentLikeService(); }
        @Bean ShopCacheVersionService shopCacheVersionService() { return new ShopCacheVersionService(); }
        @Bean ShopGeoIndexService shopGeoIndexService() { return new ShopGeoIndexService(); }
        @Bean CommentMutationGuard commentMutationGuard() { return new CommentMutationGuard(); }
        @Bean UploadAssetService uploadAssetService() { return new UploadAssetService(); }
        @Bean BlogPublicationService blogPublicationService() { return new BlogPublicationService(); }
        @Bean IBlogService blogService() { return new BlogServiceImpl(); }
        @Bean IBlogCommentsService blogCommentsService() { return new BlogCommentsServiceImpl(); }
        @Bean OrderResolutionService orderResolutionService() { return new OrderResolutionService(); }
        @Bean SeckillReservationService reservationService() { return new SeckillReservationService(); }
        @Bean RabbitTemplate rabbitTemplate() { return mock(RabbitTemplate.class); }
        @Bean MQSender mqSender() { return mock(MQSender.class); }
        @Bean RedisIdWorker redisIdWorker() { return mock(RedisIdWorker.class); }
        @Bean ISeckillVoucherService seckillVoucherService() { return new SeckillVoucherServiceImpl(); }
        @Bean IVoucherOrderService voucherOrderService() { return new VoucherOrderServiceImpl(); }
        @Bean IBlogCommentReportService reportService() { return new BlogCommentReportServiceImpl(); }
        @Bean IShopService shopService() { return new ShopServiceImpl(); }
    }

    /** Observe actual mapper result materialization, not just the trimmed DTO response. */
    @org.apache.ibatis.plugin.Intercepts(@org.apache.ibatis.plugin.Signature(
            type = org.apache.ibatis.executor.Executor.class, method = "query",
            args = {org.apache.ibatis.mapping.MappedStatement.class, Object.class,
                    org.apache.ibatis.session.RowBounds.class, org.apache.ibatis.session.ResultHandler.class}))
    static class CommentReadProbe implements org.apache.ibatis.plugin.Interceptor {
        private final List<Integer> replyRows = Collections.synchronizedList(new ArrayList<>());
        @Override
        public Object intercept(org.apache.ibatis.plugin.Invocation invocation) throws Throwable {
            Object value = invocation.proceed();
            if (value instanceof List) {
                List<?> rows = (List<?>) value;
                if (!rows.isEmpty() && rows.get(0) instanceof BlogComments
                        && ((BlogComments) rows.get(0)).getParentId() > 0) {
                    replyRows.add(rows.size());
                }
            }
            return value;
        }
    }

    @BeforeAll
    void start() throws Exception {
        DatabaseSchemaIT.initSchema(); // requires explicit isolated-database reset acknowledgement
        context = new AnnotationConfigApplicationContext(Services.class);
        jdbc = context.getBean(JdbcTemplate.class);
        likes = context.getBean(ContentLikeService.class);
        comments = context.getBean(IBlogCommentsService.class);
        shops = context.getBean(IShopService.class);
        versions = context.getBean(ShopCacheVersionService.class);
        redis = context.getBean(StringRedisTemplate.class);
        cache = context.getBean(CacheClient.class);
        transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
    }

    @AfterAll
    void stop() { if (context != null) { context.close(); } }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void resetFixtures() {
        jdbc.update("DELETE FROM tb_comment_moderation_audit");
        jdbc.update("DELETE FROM tb_blog_comment_report");
        jdbc.update("DELETE FROM tb_order_resolution");
        jdbc.update("DELETE FROM tb_voucher_order WHERE voucher_id=990099");
        jdbc.update("DELETE FROM tb_seckill_voucher WHERE voucher_id=990099");
        jdbc.update("INSERT INTO tb_seckill_voucher (voucher_id,stock,begin_time,end_time) VALUES (990099,10,NOW(),NOW())");
        jdbc.update("DELETE FROM tb_upload_asset");
        jdbc.update("DELETE FROM tb_blog WHERE title='upload fixture'");
        jdbc.update("DELETE FROM tb_content_like");
        jdbc.update("DELETE FROM tb_shop_cache_version");
        jdbc.update("DELETE FROM tb_blog_comments WHERE blog_id=?", BLOG_ID);
        jdbc.update("DELETE FROM tb_blog WHERE id=?", BLOG_ID);
        jdbc.update("INSERT INTO tb_blog (id,shop_id,user_id,title,images,content,liked,comments) VALUES (?,1,7,'fixture','','fixture',0,1)", BLOG_ID);
        jdbc.update("INSERT INTO tb_blog_comments (id,blog_id,user_id,parent_id,answer_id,content,liked,status) VALUES (?,?,7,0,0,'root',0,0)", ROOT_ID, BLOG_ID);
        reset(redis, cache, context.getBean(IUserService.class));
        context.getBean(CommentReadProbe.class).replyRows.clear();
    }

    @AfterEach
    void clearUser() { UserHolder.removeUser(); }

    private void login(long id) {
        UserDTO user = new UserDTO(); user.setId(id); UserHolder.saveUser(user);
    }

    private void parallel(int count, IntConsumer action) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(8, count));
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> tasks = new ArrayList<>();
        try {
            for (int i = 0; i < count; i++) {
                final int index = i;
                tasks.add(executor.submit(() -> {
                    try { start.await(); action.accept(index); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                    finally { UserHolder.removeUser(); }
                }));
            }
            start.countDown();
            for (Future<?> task : tasks) { task.get(30, TimeUnit.SECONDS); }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void concurrentBlogTogglesKeepCounterEqualToUniqueRelationships() throws Exception {
        parallel(20, i -> likes.toggle(BLOG, BLOG_ID, 7L));
        assertEquals(0, likes.state(BLOG, BLOG_ID, 7L).getLiked());
        assertFalse(likes.state(BLOG, BLOG_ID, 7L).isLike());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tb_content_like", Integer.class));
        parallel(20, i -> likes.toggle(BLOG, BLOG_ID, 100L + i));
        assertEquals(20, likes.state(BLOG, BLOG_ID, 100L).getLiked());
        assertEquals(20, jdbc.queryForObject("SELECT COUNT(*) FROM tb_content_like", Integer.class));
    }

    @Test
    void concurrentCommentTogglesThroughActualServiceDoNotDoubleCount() throws Exception {
        parallel(20, i -> { login(7L); assertTrue(comments.likeComment(ROOT_ID).getSuccess()); });
        assertEquals(0, likes.state(COMMENT, ROOT_ID, 7L).getLiked());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tb_content_like", Integer.class));
    }

    @Test
    void failedTransactionRollsBackRelationshipAndCounter() {
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
            likes.toggle(BLOG, BLOG_ID, 7L);
            throw new IllegalStateException("forced rollback");
        }));
        assertEquals(0, jdbc.queryForObject("SELECT liked FROM tb_blog WHERE id=?", Integer.class, BLOG_ID));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tb_content_like", Integer.class));
    }

    @Test
    void failureAfterCommentWritesRollsBackCommentAndBlogCounter() {
        login(7L);
        doThrow(new IllegalStateException("forced user lookup failure")).when(context.getBean(IUserService.class)).listByIds(anyCollection());
        BlogCommentSaveDTO request = new BlogCommentSaveDTO();
        request.setBlogId(BLOG_ID); request.setContent("must roll back");
        assertThrows(IllegalStateException.class, () -> comments.saveComment(request));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM tb_blog_comments WHERE blog_id=?", Integer.class, BLOG_ID));
        assertEquals(1, jdbc.queryForObject("SELECT comments FROM tb_blog WHERE id=?", Integer.class, BLOG_ID));
    }

    @Test
    void rollbackRestoresDeletedCommentsLikesAndCounter() {
        login(7L);
        likes.toggle(COMMENT, ROOT_ID, 9L);
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
            assertTrue(comments.deleteComment(ROOT_ID).getSuccess());
            throw new IllegalStateException("forced rollback");
        }));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM tb_blog_comments WHERE blog_id=?", Integer.class, BLOG_ID));
        assertEquals(1, jdbc.queryForObject("SELECT comments FROM tb_blog WHERE id=?", Integer.class, BLOG_ID));
        assertTrue(likes.state(COMMENT, ROOT_ID, 9L).isLike());
    }

    @Test
    void replyRacingRootDeletionLeavesNoOrphansOrCounterDrift() throws Exception {
        parallel(2, i -> {
            login(7L);
            if (i == 0) { assertTrue(comments.deleteComment(ROOT_ID).getSuccess()); }
            else {
                BlogCommentSaveDTO request = new BlogCommentSaveDTO();
                request.setBlogId(BLOG_ID); request.setParentId(ROOT_ID); request.setContent("racing reply");
                comments.saveComment(request); // May be accepted before deletion or rejected after it.
            }
        });
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tb_blog_comments WHERE blog_id=?", Integer.class, BLOG_ID));
        assertEquals(0, jdbc.queryForObject("SELECT comments FROM tb_blog WHERE id=?", Integer.class, BLOG_ID));
    }

    @Test
    void duplicateConcurrentDeletesDecrementCounterOnlyOnceAndCleanLikes() throws Exception {
        likes.toggle(COMMENT, ROOT_ID, 9L);
        parallel(2, i -> { login(7L); comments.deleteComment(ROOT_ID); });
        assertEquals(0, jdbc.queryForObject("SELECT comments FROM tb_blog WHERE id=?", Integer.class, BLOG_ID));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tb_content_like", Integer.class));
    }

    @Test
    void shopUpdateAndCacheGenerationCommitAndRollbackTogether() {
        String before = jdbc.queryForObject("SELECT name FROM tb_shop WHERE id=1", String.class);
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
            assertTrue(shops.update(new Shop().setId(1L).setName("rolled back")).getSuccess());
            assertEquals(1L, versions.current(1L));
            throw new IllegalStateException("forced rollback");
        }));
        assertEquals(before, jdbc.queryForObject("SELECT name FROM tb_shop WHERE id=1", String.class));
        assertEquals(0L, versions.current(1L));
        assertTrue(shops.update(new Shop().setId(1L).setName("committed")).getSuccess());
        assertEquals(1L, versions.current(1L));
        assertEquals("committed", jdbc.queryForObject("SELECT name FROM tb_shop WHERE id=1", String.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void lateOldCacheFillCannotPoisonNewVersionReads() {
        long oldVersion = versions.current(1L);
        assertTrue(shops.update(new Shop().setId(1L).setName("new namespace")).getSuccess());
        Map<String, Shop> cacheContents = new HashMap<>();
        cacheContents.put(CACHE_SHOP_KEY + oldVersion + ":1", new Shop().setId(1L).setName("stale late fill"));
        when(cache.queryWithPassThrough(anyString(), eq(1L), eq(Shop.class), any(), anyLong(), any())).thenAnswer(call -> {
            String key = call.<String>getArgument(0) + call.getArgument(1);
            Function<Long, Shop> fallback = call.getArgument(3);
            return cacheContents.containsKey(key) ? cacheContents.get(key) : fallback.apply(1L);
        });
        Shop actual = (Shop) shops.queryById(1L).getData();
        assertEquals("new namespace", actual.getName());
    }

    private void insertReply(long id, long userId, int status, long answerId) {
        jdbc.update("INSERT INTO tb_blog_comments (id,blog_id,user_id,parent_id,answer_id,content,liked,status,create_time) "
                + "VALUES (?,?,?,?,?,'reply',0,?,'2026-01-01 00:00:00')", id, BLOG_ID, userId, ROOT_ID, answerId, status);
    }

    @SuppressWarnings("unchecked")
    private List<BlogCommentDTO> dtos(Result result) {
        assertTrue(result.getSuccess(), result.getErrorMsg());
        return (List<BlogCommentDTO>) result.getData();
    }

    @Test
    void previewReadsAtMostThreeReplyEntitiesAndRetainsVisibleTotal() {
        for (int i = 0; i < 50; i++) { insertReply(900100L + i, 8L, i < 40 ? 0 : 2, 0L); }
        List<BlogCommentDTO> roots = dtos(comments.queryCommentsByBlogId(BLOG_ID, 1));
        assertEquals(1, roots.size());
        assertEquals(40, roots.get(0).getReplyCount());
        assertEquals(3, roots.get(0).getReplies().size());
        assertEquals(900100L, roots.get(0).getReplies().get(0).getId());
        List<Integer> materialized = context.getBean(CommentReadProbe.class).replyRows;
        assertFalse(materialized.isEmpty());
        assertTrue(materialized.stream().allMatch(size -> size <= 3), materialized.toString());
    }

    @Test
    void previewLimitAppliesSeparatelyToEveryRoot() {
        jdbc.update("INSERT INTO tb_blog_comments (id,blog_id,user_id,parent_id,answer_id,content,liked,status) "
                + "VALUES (900012,?,7,0,0,'second root',0,0)", BLOG_ID);
        for (int i = 0; i < 8; i++) { insertReply(900100L + i, 8L, 0, 0L); }
        jdbc.update("UPDATE tb_blog_comments SET parent_id=900012 WHERE id BETWEEN 900104 AND 900107");
        List<BlogCommentDTO> roots = dtos(comments.queryCommentsByBlogId(BLOG_ID, 1));
        assertEquals(2, roots.size());
        for (BlogCommentDTO root : roots) {
            assertEquals(4, root.getReplyCount());
            assertEquals(3, root.getReplies().size());
            assertTrue(root.getReplies().stream().allMatch(reply -> root.getId().equals(reply.getParentId())));
        }
    }

    @Test
    void expandedRepliesArePaginatedAndExcludeBlockedRowsFromTotal() {
        for (int i = 0; i < 25; i++) { insertReply(900100L + i, 8L, i < 20 ? 0 : 2, 0L); }
        Result page = comments.queryRepliesByParentId(ROOT_ID, 2);
        List<BlogCommentDTO> rows = dtos(page);
        assertEquals(20L, page.getTotal());
        assertEquals(10, rows.size());
        assertEquals(900110L, rows.get(0).getId());
        assertEquals(900119L, rows.get(9).getId());
        assertTrue(dtos(comments.queryRepliesByParentId(ROOT_ID, 3)).isEmpty());
    }

    @Test
    void blockedRootCannotBeBypassedByGuestOrOtherReplyAuthor() {
        jdbc.update("UPDATE tb_blog_comments SET status=2 WHERE id=?", ROOT_ID);
        insertReply(900100L, 8L, 0, 0L);
        assertTrue(dtos(comments.queryCommentsByBlogId(BLOG_ID, 1)).isEmpty());
        assertFalse(comments.queryRepliesByParentId(ROOT_ID, 1).getSuccess());
        login(8L);
        assertFalse(comments.queryRepliesByParentId(ROOT_ID, 1).getSuccess());
        assertFalse(comments.likeComment(900100L).getSuccess());
        login(7L);
        assertEquals(1, dtos(comments.queryRepliesByParentId(ROOT_ID, 1)).size());
    }

    @Test
    void blockedReplyAndRootRejectUnauthorizedLikesWithoutWritingRelationships() {
        insertReply(900100L, 8L, 2, 0L);
        login(7L);
        assertFalse(comments.likeComment(900100L).getSuccess());
        jdbc.update("UPDATE tb_blog_comments SET status=2 WHERE id=?", ROOT_ID);
        login(8L);
        assertFalse(comments.likeComment(ROOT_ID).getSuccess());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tb_content_like", Integer.class));
    }

    @Test
    void blockedParentsAndAnswersRejectRepliesWithoutChangingCounter() {
        insertReply(900100L, 8L, 2, 0L);
        login(7L);
        BlogCommentSaveDTO request = new BlogCommentSaveDTO();
        request.setBlogId(BLOG_ID); request.setParentId(ROOT_ID); request.setAnswerId(900100L); request.setContent("denied");
        assertFalse(comments.saveComment(request).getSuccess());
        jdbc.update("UPDATE tb_blog_comments SET status=2 WHERE id=?", ROOT_ID);
        login(8L); request.setAnswerId(0L);
        assertFalse(comments.saveComment(request).getSuccess());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM tb_blog_comments WHERE blog_id=?", Integer.class, BLOG_ID));
        assertEquals(1, jdbc.queryForObject("SELECT comments FROM tb_blog WHERE id=?", Integer.class, BLOG_ID));
    }

    @Test
    void blockedAnswerIdentityIsRedactedInPreviewAndExpandedReplies() {
        insertReply(900100L, 8L, 2, 0L);
        insertReply(900101L, 7L, 0, 900100L);
        when(context.getBean(IUserService.class).listByIds(anyCollection()))
                .thenReturn(Arrays.asList(new User().setId(7L).setNickName("public"), new User().setId(8L).setNickName("secret")));
        BlogCommentDTO preview = dtos(comments.queryCommentsByBlogId(BLOG_ID, 1)).get(0).getReplies().get(0);
        BlogCommentDTO expanded = dtos(comments.queryRepliesByParentId(ROOT_ID, 1)).get(0);
        assertEquals(0L, preview.getAnswerId());
        assertEquals("评论不可见或已删除", preview.getAnswerNickName());
        assertEquals(0L, expanded.getAnswerId());
        assertEquals("评论不可见或已删除", expanded.getAnswerNickName());
        login(8L);
        assertEquals(2, dtos(comments.queryCommentsByBlogId(BLOG_ID, 1)).get(0).getReplyCount());
        assertEquals("secret", dtos(comments.queryRepliesByParentId(ROOT_ID, 1)).get(1).getAnswerNickName());
    }

    @Test
    void answerOutsidePreviewIsResolvedButCrossThreadReferenceIsRedacted() {
        insertReply(900100L, 7L, 0, 900110L);
        for (int i = 1; i <= 10; i++) { insertReply(900100L + i, 8L, 0, 0L); }
        when(context.getBean(IUserService.class).listByIds(anyCollection()))
                .thenReturn(Collections.singletonList(new User().setId(8L).setNickName("outside preview")));
        BlogCommentDTO reply = dtos(comments.queryCommentsByBlogId(BLOG_ID, 1)).get(0).getReplies().get(0);
        assertEquals("outside preview", reply.getAnswerNickName());
        jdbc.update("UPDATE tb_blog_comments SET parent_id=0 WHERE id=900110");
        reply = dtos(comments.queryRepliesByParentId(ROOT_ID, 1)).get(0);
        assertEquals(0L, reply.getAnswerId());
        assertEquals("评论不可见或已删除", reply.getAnswerNickName());
    }

    private String draft(long owner) {
        String path = "blogs/0/1/" + UUID.randomUUID() + ".png";
        UploadAssetService assets = context.getBean(UploadAssetService.class);
        assets.reserve(path, owner); assets.ready(path, owner);
        return path;
    }

    private Blog uploadBlog(String images) {
        return new Blog().setShopId(1L).setTitle("upload fixture").setImages(images).setContent("upload fixture");
    }

    private String assetState(String path) {
        return jdbc.queryForObject("SELECT state FROM tb_upload_asset WHERE path=?", String.class, path);
    }

    @Test
    void uploadOwnershipSurvivesAgeAndRedisLossAndDeletionIsIdempotent() {
        String path = draft(7L);
        jdbc.update("UPDATE tb_upload_asset SET create_time=DATE_SUB(NOW(), INTERVAL 3 DAY),update_time=DATE_SUB(NOW(), INTERVAL 3 DAY) WHERE path=?", path);
        doThrow(new RedisConnectionFailureException("offline")).when(redis).opsForValue();
        UploadAssetService assets = context.getBean(UploadAssetService.class);
        assertEquals(UploadAssetService.DeleteDecision.DENIED, assets.beginDelete(path, 8L));
        assertEquals(UploadAssetService.DeleteDecision.DELETE, assets.beginDelete("/uploads/" + path, 7L));
        assets.finishDelete(path, 7L); assets.finishDelete(path, 7L);
        assertEquals(UploadAssetService.DeleteDecision.ALREADY_DELETED, assets.beginDelete(path, 7L));
        assertEquals("DELETED", assetState(path));
    }

    @Test
    void publishedImageIsBoundAtomicallyAndCannotBeDeletedByOwner() {
        String path = draft(7L);
        Blog blog = uploadBlog("/uploads/" + path).setUserId(88L).setLiked(900).setComments(900);
        context.getBean(BlogPublicationService.class).publish(blog, 7L);
        assertEquals("BOUND", assetState(path));
        assertEquals(blog.getId(), jdbc.queryForObject("SELECT blog_id FROM tb_upload_asset WHERE path=?", Long.class, path));
        assertEquals("/" + path, jdbc.queryForObject("SELECT images FROM tb_blog WHERE id=?", String.class, blog.getId()));
        assertEquals(7L, jdbc.queryForObject("SELECT user_id FROM tb_blog WHERE id=?", Long.class, blog.getId()));
        assertEquals(0, jdbc.queryForObject("SELECT liked FROM tb_blog WHERE id=?", Integer.class, blog.getId()));
        assertEquals(UploadAssetService.DeleteDecision.DENIED, context.getBean(UploadAssetService.class).beginDelete(path, 7L));
    }

    @Test
    void unauthorizedImageRollsBackBlogAndAnyEarlierImageBindings() {
        String first = "blogs/0/1/00000000-0000-0000-0000-000000000001.png";
        String second = "blogs/0/1/00000000-0000-0000-0000-000000000002.png";
        UploadAssetService assets = context.getBean(UploadAssetService.class);
        assets.reserve(first, 7L); assets.ready(first, 7L);
        assets.reserve(second, 8L); assets.ready(second, 8L);
        assertThrows(IllegalArgumentException.class, () -> context.getBean(BlogPublicationService.class).publish(uploadBlog(first + "," + second), 7L));
        assertEquals("READY", assetState(first)); assertEquals("READY", assetState(second));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tb_blog WHERE title='upload fixture'", Integer.class));
    }

    @Test
    void draftDeleteRacingPublicationCannotProduceBoundDeletedImage() throws Exception {
        String path = draft(7L);
        parallel(2, i -> {
            if (i == 0) { context.getBean(UploadAssetService.class).beginDelete(path, 7L); }
            else {
                try { context.getBean(BlogPublicationService.class).publish(uploadBlog(path), 7L); }
                catch (IllegalArgumentException deletedFirst) { assertTrue(deletedFirst.getMessage().contains("图片不可用")); }
            }
        });
        String state = assetState(path);
        assertTrue("BOUND".equals(state) || "DELETING".equals(state));
        assertEquals("BOUND".equals(state) ? 1 : 0,
                jdbc.queryForObject("SELECT COUNT(*) FROM tb_blog WHERE title='upload fixture'", Integer.class));
    }

    @Test
    void unregisteredImagesAreNeverClaimedOrDeleted() {
        String path = "blogs/0/1/00000000-0000-0000-0000-000000000003.png";
        jdbc.update("UPDATE tb_blog SET images=? WHERE id=?", "/" + path, BLOG_ID);
        assertEquals(UploadAssetService.DeleteDecision.DENIED, context.getBean(UploadAssetService.class).beginDelete(path, 7L));
        assertThrows(IllegalArgumentException.class, () -> context.getBean(BlogPublicationService.class).publish(uploadBlog(path), 7L));
        assertEquals("/" + path, jdbc.queryForObject("SELECT images FROM tb_blog WHERE id=?", String.class, BLOG_ID));
    }

    @Test
    void deletingFailedOrIncompleteUploadsCannotBePublished() {
        UploadAssetService assets = context.getBean(UploadAssetService.class);
        for (String state : Arrays.asList("UPLOADING", "FAILED", "DELETING", "DELETED")) {
            String path = draft(7L);
            jdbc.update("UPDATE tb_upload_asset SET state=? WHERE path=?", state, path);
            assertThrows(IllegalArgumentException.class, () -> context.getBean(BlogPublicationService.class).publish(uploadBlog(path), 7L));
            assertEquals(state, assetState(path));
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tb_blog WHERE title='upload fixture'", Integer.class));
    }

    @Test
    void duplicateReservationPreservesOwnership() {
        String path = draft(7L);
        assertThrows(org.springframework.dao.DuplicateKeyException.class, () -> context.getBean(UploadAssetService.class).reserve(path, 8L));
        assertEquals(7L, jdbc.queryForObject("SELECT user_id FROM tb_upload_asset WHERE path=?", Long.class, path));
        assertEquals("READY", assetState(path));
    }

    @Test
    void concurrentDeleteAcknowledgementsAreIdempotent() throws Exception {
        String path = draft(7L);
        UploadAssetService assets = context.getBean(UploadAssetService.class);
        assertEquals(UploadAssetService.DeleteDecision.DELETE, assets.beginDelete(path, 7L));
        parallel(4, i -> assets.finishDelete(path, 7L));
        assertEquals("DELETED", assetState(path));
    }

    @Test
    void importedDraftReferencedByExistingBlogIsProtected() {
        String path = draft(7L);
        jdbc.update("UPDATE tb_blog SET images=? WHERE id=?", "/uploads/" + path, BLOG_ID);
        assertEquals(UploadAssetService.DeleteDecision.DENIED, context.getBean(UploadAssetService.class).beginDelete(path, 7L));
        assertEquals("READY", assetState(path));
    }

    @Test
    void emptyDuplicateAndTooManyImagesRollBackPublication() {
        String path = draft(7L);
        List<String> ten = new ArrayList<>();
        for (int i = 0; i < 10; i++) { ten.add("blogs/0/1/" + UUID.randomUUID() + ".png"); }
        for (String images : Arrays.asList("", path + "," + path, String.join(",", ten))) {
            assertThrows(IllegalArgumentException.class, () -> context.getBean(BlogPublicationService.class).publish(uploadBlog(images), 7L));
        }
        assertEquals("READY", assetState(path));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tb_blog WHERE title='upload fixture'", Integer.class));
    }

    private VoucherOrder order(long id) { return new VoucherOrder().setId(id).setUserId(7L).setVoucherId(990099L); }

    @Test
    void redeliveryAfterDatabaseCommitDoesNotDeductOrRefundTwice() {
        IVoucherOrderService orders = context.getBean(IVoucherOrderService.class);
        assertTrue(orders.createVoucherOrder(order(990001L)));
        assertTrue(orders.createVoucherOrder(order(990001L)));
        assertFalse(context.getBean(OrderResolutionService.class).cancel(order(990001L)));
        assertEquals(9, jdbc.queryForObject("SELECT stock FROM tb_seckill_voucher WHERE voucher_id=990099", Integer.class));
    }

    @Test
    void compensationFenceRejectsLateDelivery() {
        assertTrue(context.getBean(OrderResolutionService.class).cancel(order(990001L)));
        assertThrows(OrderResolutionService.Cancelled.class, () -> context.getBean(IVoucherOrderService.class).createVoucherOrder(order(990001L)));
        assertEquals(10, jdbc.queryForObject("SELECT stock FROM tb_seckill_voucher WHERE voucher_id=990099", Integer.class));
    }

    @Test
    void cancellationRacingConsumerHasExactlyOneDatabaseOutcome() throws Exception {
        parallel(2, i -> {
            if (i == 0) { context.getBean(OrderResolutionService.class).cancel(order(990001L)); }
            else {
                try { context.getBean(IVoucherOrderService.class).createVoucherOrder(order(990001L)); }
                catch (OrderResolutionService.Cancelled expected) { /* cancellation won */ }
            }
        });
        int count = jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order WHERE id=990001", Integer.class);
        assertEquals(10 - count, jdbc.queryForObject("SELECT stock FROM tb_seckill_voucher WHERE voucher_id=990099", Integer.class));
        if (count == 1) { assertFalse(context.getBean(OrderResolutionService.class).cancel(order(990001L))); }
        else { assertThrows(OrderResolutionService.Cancelled.class, () -> context.getBean(IVoucherOrderService.class).createVoucherOrder(order(990001L))); }
    }

    @Test
    void fenceRefusesMismatchedOrderIdentity() {
        context.getBean(OrderResolutionService.class).cancel(order(990001L));
        assertThrows(IllegalArgumentException.class, () -> context.getBean(OrderResolutionService.class).cancel(order(990001L).setUserId(8L)));
    }

    @Test
    void moderationRecordsActorAndAtomicallyClosesReports() {
        IBlogCommentReportService reports = context.getBean(IBlogCommentReportService.class);
        login(8L); assertTrue(reports.reportComment(ROOT_ID, "fixture reason").getSuccess());
        login(1L); assertTrue(reports.blockComment(ROOT_ID).getSuccess());
        assertEquals(2, jdbc.queryForObject("SELECT status FROM tb_blog_comments WHERE id=?", Integer.class, ROOT_ID));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tb_blog_comment_report WHERE handled=0", Integer.class));
        assertEquals(1L, jdbc.queryForObject("SELECT actor_id FROM tb_comment_moderation_audit LIMIT 1", Long.class));
        assertTrue(reports.restoreComment(ROOT_ID).getSuccess());
        assertEquals(2L, reports.queryHistory(1).getTotal());
    }

    @Test
    void moderationRollbackRestoresStatusReportsAndAudit() {
        IBlogCommentReportService reports = context.getBean(IBlogCommentReportService.class);
        login(8L); assertTrue(reports.reportComment(ROOT_ID, "reason").getSuccess()); login(1L);
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(tx -> {
            assertTrue(reports.blockComment(ROOT_ID).getSuccess()); throw new IllegalStateException("forced failure");
        }));
        assertEquals(1, jdbc.queryForObject("SELECT status FROM tb_blog_comments WHERE id=?", Integer.class, ROOT_ID));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM tb_blog_comment_report WHERE handled=0", Integer.class));
        assertEquals(0L, reports.queryHistory(1).getTotal());
    }

    @Test
    void invisibleRootCannotBeReportedThroughChildAndDeletedReportCanBeClosed() {
        IBlogCommentReportService reports = context.getBean(IBlogCommentReportService.class);
        insertReply(900100L, 9L, 0, 0L); login(8L);
        jdbc.update("UPDATE tb_blog_comments SET status=2 WHERE id=?", ROOT_ID);
        assertFalse(reports.reportComment(900100L, "reason").getSuccess());
        jdbc.update("UPDATE tb_blog_comments SET status=0 WHERE id=?", ROOT_ID);
        assertTrue(reports.reportComment(ROOT_ID, "reason").getSuccess());
        login(7L); assertTrue(comments.deleteComment(ROOT_ID).getSuccess());
        login(1L); assertTrue(reports.dismissReports(ROOT_ID).getSuccess());
        assertEquals(0L, reports.queryPendingReports(1).getTotal());
        assertEquals(1L, reports.queryHistory(1).getTotal());
    }

    @Test
    void avatarBindingPreventsDeletionAndRollbackKeepsOwnershipState() {
        String path = draft(1L);
        UploadAssetService assets = context.getBean(UploadAssetService.class);
        transaction.executeWithoutResult(tx -> {
            assets.bindAvatar("/uploads/" + path, 1L);
            jdbc.update("UPDATE tb_user SET icon=? WHERE id=1", "/uploads/" + path);
        });
        assertEquals(UploadAssetService.DeleteDecision.DENIED, assets.beginDelete(path, 1L));
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(tx -> {
            assets.bindAvatar("", 1L); throw new IllegalStateException("rollback");
        }));
        assertEquals("AVATAR", assetState(path));
        transaction.executeWithoutResult(tx -> { assets.bindAvatar("", 1L); jdbc.update("UPDATE tb_user SET icon='' WHERE id=1"); });
        assertEquals(UploadAssetService.DeleteDecision.DELETE, assets.beginDelete(path, 1L));
    }

}
