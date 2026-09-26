package com.yuelin.service.impl;

import com.yuelin.dto.Result;
import com.yuelin.dto.UserDTO;
import com.yuelin.entity.BlogCommentReport;
import com.yuelin.entity.BlogComments;
import com.yuelin.service.IBlogCommentsService;
import com.yuelin.utils.CommentStatus;
import com.yuelin.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 举报与审核的参数、权限与状态流转校验。全部分支在触达数据库前返回，无需真实基础设施。
 */
class BlogCommentReportServiceImplTest {

    private BlogCommentReportServiceImpl service;
    private IBlogCommentsService blogCommentsService;
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        service = spy(new BlogCommentReportServiceImpl());
        blogCommentsService = mock(IBlogCommentsService.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        ReflectionTestUtils.setField(service, "blogCommentsService", blogCommentsService);
        ReflectionTestUtils.setField(service, "jdbcTemplate", jdbcTemplate);
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

    private BlogComments comment(long id, long authorId, int status) {
        return new BlogComments().setId(id).setBlogId(1L).setParentId(0L)
                .setUserId(authorId).setStatus(status);
    }

    @Test
    void rejectsReportWithInvalidCommentId() {
        login(7L);

        Result result = service.reportComment(0L, "垃圾广告");

        assertFalse(result.getSuccess());
        verifyNoInteractions(blogCommentsService);
    }

    @Test
    void rejectsReportWhenNotLoggedIn() {
        Result result = service.reportComment(4L, "垃圾广告");

        assertFalse(result.getSuccess());
        verifyNoInteractions(blogCommentsService);
    }

    @Test
    void rejectsReportWithBlankReason() {
        login(7L);

        Result result = service.reportComment(4L, "   ");

        assertFalse(result.getSuccess());
        verifyNoInteractions(blogCommentsService);
    }

    @Test
    void rejectsReasonLongerThanColumnLimit() {
        login(7L);
        StringBuilder tooLong = new StringBuilder();
        for (int i = 0; i <= BlogCommentReportServiceImpl.MAX_REASON_LENGTH; i++) {
            tooLong.append('x');
        }

        Result result = service.reportComment(4L, tooLong.toString());

        assertFalse(result.getSuccess());
        verifyNoInteractions(blogCommentsService);
    }

    @Test
    void rejectsReportForMissingComment() {
        login(7L);
        when(blogCommentsService.getById(4L)).thenReturn(null);

        Result result = service.reportComment(4L, "垃圾广告");

        assertFalse(result.getSuccess());
        verify(service, never()).save(any(BlogCommentReport.class));
    }

    @Test
    void rejectsReportingOwnComment() {
        login(7L);
        when(blogCommentsService.getById(4L)).thenReturn(comment(4L, 7L, CommentStatus.NORMAL));

        Result result = service.reportComment(4L, "垃圾广告");

        assertFalse(result.getSuccess());
        verify(service, never()).save(any(BlogCommentReport.class));
    }

    @Test
    void rejectsReportForAlreadyBlockedComment() {
        login(7L);
        when(blogCommentsService.getById(4L)).thenReturn(comment(4L, 8L, CommentStatus.BLOCKED));

        Result result = service.reportComment(4L, "垃圾广告");

        assertFalse(result.getSuccess());
        verify(service, never()).save(any(BlogCommentReport.class));
    }

    @Test
    void rejectsDuplicateReportFromSameUser() {
        login(7L);
        when(blogCommentsService.getById(4L)).thenReturn(comment(4L, 8L, CommentStatus.NORMAL));
        doReturn(1L).when(service).count(any());

        Result result = service.reportComment(4L, "垃圾广告");

        assertFalse(result.getSuccess());
        verify(service, never()).save(any(BlogCommentReport.class));
    }

    @Test
    void rejectsBlockWithInvalidCommentId() {
        Result result = service.blockComment(null);

        assertFalse(result.getSuccess());
        verifyNoInteractions(blogCommentsService);
    }

    @Test
    void rejectsBlockForMissingComment() {
        when(blogCommentsService.getById(4L)).thenReturn(null);

        Result result = service.blockComment(4L);

        assertFalse(result.getSuccess());
        verify(blogCommentsService, never()).update();
    }

    @Test
    void rejectsRestoreForMissingComment() {
        when(blogCommentsService.getById(4L)).thenReturn(null);

        Result result = service.restoreComment(4L);

        assertFalse(result.getSuccess());
        verify(blogCommentsService, never()).update();
    }

    @Test
    void historyCastsAllBigintIdentifiersToText() {
        service.queryHistory(1);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForList(sql.capture(), eq(0L));
        String query = sql.getValue();
        assertTrue(query.contains("CAST(id AS CHAR) AS id"));
        assertTrue(query.contains("CAST(comment_id AS CHAR) AS comment_id"));
        assertTrue(query.contains("CAST(actor_id AS CHAR) AS actor_id"));
    }

    @Test
    void normalizesNullStatusToNormal() {
        assertEquals(CommentStatus.NORMAL, CommentStatus.normalize(null));
        assertFalse(CommentStatus.isBlocked(null));
    }

    @Test
    void recognizesOnlyDefinedStatusValues() {
        assertTrue(CommentStatus.isValid(CommentStatus.NORMAL));
        assertTrue(CommentStatus.isValid(CommentStatus.REPORTED));
        assertTrue(CommentStatus.isValid(CommentStatus.BLOCKED));
        assertFalse(CommentStatus.isValid(3));
        assertFalse(CommentStatus.isValid(-1));
    }
}
