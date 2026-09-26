package com.yuelin.service.impl;

import com.yuelin.dto.BlogCommentSaveDTO;
import com.yuelin.dto.Result;
import com.yuelin.dto.UserDTO;
import com.yuelin.entity.Blog;
import com.yuelin.entity.BlogComments;
import com.yuelin.service.IBlogService;
import com.yuelin.service.IUserService;
import com.yuelin.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.yuelin.service.ContentLikeService;
import com.yuelin.service.CommentMutationGuard;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 评论服务的参数、权限与边界校验测试。这些分支在触达数据库前返回，因此无需真实基础设施。
 */
class BlogCommentsServiceImplTest {

    private BlogCommentsServiceImpl service;
    private IBlogService blogService;
    private IUserService userService;
    private ContentLikeService likes;
    private CommentMutationGuard guard;

    @BeforeEach
    void setUp() {
        service = spy(new BlogCommentsServiceImpl());
        blogService = mock(IBlogService.class);
        userService = mock(IUserService.class);
        likes = mock(ContentLikeService.class);
        guard = mock(CommentMutationGuard.class);
        when(guard.lockBlog(any())).thenReturn(true);
        ReflectionTestUtils.setField(service, "blogService", blogService);
        ReflectionTestUtils.setField(service, "userService", userService);
        ReflectionTestUtils.setField(service, "contentLikeService", likes);
        ReflectionTestUtils.setField(service, "mutationGuard", guard);
    }

    @AfterEach
    void clearUser() {
        UserHolder.removeUser();
    }

    private void login(long id) {
        UserDTO user = new UserDTO();
        user.setId(id);
        UserHolder.saveUser(user);
    }

    private BlogCommentSaveDTO request(Long blogId, String content) {
        BlogCommentSaveDTO dto = new BlogCommentSaveDTO();
        dto.setBlogId(blogId);
        dto.setContent(content);
        return dto;
    }

    @Test
    void rejectsQueryWithInvalidBlogId() {
        Result result = service.queryCommentsByBlogId(0L, 1);

        assertFalse(result.getSuccess());
        verifyNoInteractions(userService);
    }

    @Test
    void rejectsCommentWhenNotLoggedIn() {
        Result result = service.saveComment(request(1L, "很棒的分享"));

        assertFalse(result.getSuccess());
        verifyNoInteractions(blogService);
    }

    @Test
    void rejectsBlankContentBeforeLoadingBlog() {
        login(7L);

        Result result = service.saveComment(request(1L, "   "));

        assertFalse(result.getSuccess());
        verifyNoInteractions(blogService);
    }

    @Test
    void rejectsContentLongerThanColumnLimit() {
        login(7L);
        String tooLong = repeat('a', BlogCommentsServiceImpl.MAX_CONTENT_LENGTH + 1);

        Result result = service.saveComment(request(1L, tooLong));

        assertFalse(result.getSuccess());
        verifyNoInteractions(blogService);
    }

    @Test
    void rejectsCommentOnMissingBlog() {
        login(7L);
        when(blogService.getById(99L)).thenReturn(null);

        Result result = service.saveComment(request(99L, "内容"));

        assertFalse(result.getSuccess());
        verify(service, never()).save(any(BlogComments.class));
    }

    @Test
    void rejectsReplyWhenParentBelongsToAnotherBlog() {
        login(7L);
        when(blogService.getById(1L)).thenReturn(new Blog().setId(1L));
        doReturn(new BlogComments().setId(5L).setBlogId(2L).setParentId(0L))
                .when(service).getById(5L);

        BlogCommentSaveDTO dto = request(1L, "回复内容");
        dto.setParentId(5L);

        Result result = service.saveComment(dto);

        assertFalse(result.getSuccess());
        verify(service, never()).save(any(BlogComments.class));
    }

    @Test
    void rejectsNestedReplyDeeperThanOneLevel() {
        login(7L);
        when(blogService.getById(1L)).thenReturn(new Blog().setId(1L));
        doReturn(new BlogComments().setId(5L).setBlogId(1L).setParentId(3L))
                .when(service).getById(5L);

        BlogCommentSaveDTO dto = request(1L, "回复内容");
        dto.setParentId(5L);

        Result result = service.saveComment(dto);

        assertFalse(result.getSuccess());
        verify(service, never()).save(any(BlogComments.class));
    }

    @Test
    void rejectsAnswerIdWithoutParentId() {
        login(7L);
        when(blogService.getById(1L)).thenReturn(new Blog().setId(1L));

        BlogCommentSaveDTO dto = request(1L, "回复内容");
        dto.setAnswerId(9L);

        Result result = service.saveComment(dto);

        assertFalse(result.getSuccess());
        verify(service, never()).save(any(BlogComments.class));
    }

    @Test
    void rejectsDeletingCommentOwnedByAnotherUser() {
        login(7L);
        doReturn(new BlogComments().setId(4L).setUserId(8L).setBlogId(1L).setParentId(0L))
                .when(service).getById(4L);

        Result result = service.deleteComment(4L);

        assertFalse(result.getSuccess());
        verify(service, never()).removeById(any(Long.class));
        verifyNoInteractions(blogService);
    }

    @Test
    void rejectsDeletingMissingComment() {
        login(7L);
        doReturn(null).when(service).getById(4L);

        Result result = service.deleteComment(4L);

        assertFalse(result.getSuccess());
        verifyNoInteractions(blogService);
    }

    @Test
    void rejectsDeleteWithInvalidId() {
        login(7L);

        Result result = service.deleteComment(null);

        assertFalse(result.getSuccess());
        verify(service, never()).getById(any(Long.class));
    }

    @Test
    void trimsContentAndKeepsBoundaryLengthAllowed() {
        login(7L);
        when(blogService.getById(1L)).thenReturn(new Blog().setId(1L));
        String boundary = repeat('b', BlogCommentsServiceImpl.MAX_CONTENT_LENGTH);
        doReturn(false).when(service).save(any(BlogComments.class));

        Result result = service.saveComment(request(1L, "  " + boundary + "  "));

        // 长度正好等于上限时不应该被长度校验拦下，失败原因只可能来自持久化。
        assertFalse(result.getSuccess());
        assertEquals("评论失败，请稍后重试", result.getErrorMsg());
    }

    @Test
    void previewReplyLimitIsPositive() {
        assertTrue(BlogCommentsServiceImpl.MAX_PREVIEW_REPLIES > 0);
    }

    @Test
    void rejectsReplyPageForInvalidParentId() {
        Result result = service.queryRepliesByParentId(0L, 1);

        assertFalse(result.getSuccess());
        verifyNoInteractions(userService);
    }

    @Test
    void rejectsReplyPageForMissingParent() {
        doReturn(null).when(service).getById(6L);

        Result result = service.queryRepliesByParentId(6L, 1);

        assertFalse(result.getSuccess());
        verifyNoInteractions(userService);
    }

    @Test
    void rejectsReplyPageWhenTargetIsItselfAReply() {
        doReturn(new BlogComments().setId(6L).setBlogId(1L).setParentId(2L))
                .when(service).getById(6L);

        Result result = service.queryRepliesByParentId(6L, 1);

        assertFalse(result.getSuccess());
        verifyNoInteractions(userService);
    }

    @Test
    void rejectsLikeWhenNotLoggedIn() {
        Result result = service.likeComment(4L);

        assertFalse(result.getSuccess());
        verifyNoInteractions(likes);
    }

    @Test
    void rejectsLikeWithInvalidId() {
        login(7L);

        Result result = service.likeComment(null);

        assertFalse(result.getSuccess());
        verifyNoInteractions(likes);
    }

    @Test
    void rejectsLikeForMissingComment() {
        login(7L);
        doReturn(null).when(service).getById(4L);

        Result result = service.likeComment(4L);

        assertFalse(result.getSuccess());
        verifyNoInteractions(likes);
    }

    @Test
    void likePayloadKeepsUnsafeJavaScriptIdentifierExact() {
        long commentId = 9007199254740993L;
        login(7L);
        BlogComments root = new BlogComments()
                .setId(commentId)
                .setBlogId(1L)
                .setParentId(0L)
                .setUserId(8L)
                .setStatus(0);
        doReturn(root).when(service).getById(commentId);
        when(guard.lockComment(commentId)).thenReturn(root);
        when(likes.toggle(ContentLikeService.Target.COMMENT, commentId, 7L))
                .thenReturn(new ContentLikeService.State(3, true));

        Result result = service.likeComment(commentId);

        assertTrue(result.getSuccess());
        Map<?, ?> payload = (Map<?, ?>) result.getData();
        assertEquals("9007199254740993", payload.get("id"));
        assertEquals(3, payload.get("liked"));
        assertTrue((Boolean) payload.get("isLike"));
    }

    @Test
    void hiddenRootCannotBeExpandedByGuest() {
        doReturn(new BlogComments().setId(6L).setBlogId(1L).setParentId(0L).setUserId(8L).setStatus(2))
                .when(service).getById(6L);
        assertFalse(service.queryRepliesByParentId(6L, 1).getSuccess());
        verifyNoInteractions(userService, likes);
    }

    @Test
    void likeRechecksCurrentVisibilityAfterLocking() {
        login(7L);
        BlogComments before = new BlogComments().setId(6L).setBlogId(1L).setParentId(0L).setUserId(8L).setStatus(0);
        doReturn(before).when(service).getById(6L);
        when(guard.lockComment(6L)).thenReturn(new BlogComments().setId(6L).setBlogId(1L).setParentId(0L).setUserId(8L).setStatus(2));
        assertFalse(service.likeComment(6L).getSuccess());
        verifyNoInteractions(likes);
    }

    private String repeat(char ch, int times) {
        StringBuilder builder = new StringBuilder(times);
        for (int i = 0; i < times; i++) {
            builder.append(ch);
        }
        return builder.toString();
    }
}
