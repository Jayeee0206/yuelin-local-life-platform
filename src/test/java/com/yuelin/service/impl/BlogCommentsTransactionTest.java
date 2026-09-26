package com.yuelin.service.impl;

import com.baomidou.mybatisplus.extension.conditions.update.UpdateChainWrapper;
import com.yuelin.dto.BlogCommentSaveDTO;
import com.yuelin.dto.UserDTO;
import com.yuelin.entity.Blog;
import com.yuelin.entity.BlogComments;
import com.yuelin.service.IBlogCommentsService;
import com.yuelin.service.IBlogService;
import com.yuelin.service.ContentLikeService;
import com.yuelin.service.CommentMutationGuard;
import com.yuelin.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BlogCommentsTransactionTest {
    private BlogCommentsServiceImpl target;
    private IBlogCommentsService service;
    private IBlogService blogs;
    private CommentMutationGuard guard;
    private PlatformTransactionManager transactions;
    private SimpleTransactionStatus status;

    @BeforeEach
    void setUp() {
        target = spy(new BlogCommentsServiceImpl());
        blogs = mock(IBlogService.class);
        ReflectionTestUtils.setField(target, "blogService", blogs);
        guard = mock(CommentMutationGuard.class);
        when(guard.lockBlog(any())).thenReturn(true);
        ReflectionTestUtils.setField(target, "mutationGuard", guard);
        ReflectionTestUtils.setField(target, "contentLikeService", mock(ContentLikeService.class));
        transactions = mock(PlatformTransactionManager.class);
        status = new SimpleTransactionStatus();
        when(transactions.getTransaction(any())).thenReturn(status);
        ProxyFactory factory = new ProxyFactory(target);
        factory.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        service = (IBlogCommentsService) factory.getProxy();
        UserDTO user = new UserDTO();
        user.setId(7L);
        UserHolder.saveUser(user);
    }

    @AfterEach
    void cleanUp() { UserHolder.removeUser(); }

    private void parentAndReply() {
        doReturn(new BlogComments().setId(4L).setUserId(7L).setBlogId(1L).setParentId(0L))
                .when(target).getById(4L);
        when(guard.lockComment(4L)).thenReturn(new BlogComments().setId(4L).setUserId(7L).setBlogId(1L).setParentId(0L));
        when(guard.lockReplies(4L)).thenReturn(Collections.singletonList(new BlogComments().setId(5L).setParentId(4L)));
    }

    private void assertRolledBack() {
        verify(transactions).rollback(status);
        verify(transactions, never()).commit(any());
    }

    @Test
    void rollsBackReplyDeletionWhenParentDeleteFails() {
        parentAndReply();
        doReturn(true).when(target).removeByIds(Collections.singletonList(5L));
        doReturn(false).when(target).removeById(4L);
        assertThrows(IllegalStateException.class, () -> service.deleteComment(4L));
        assertRolledBack();
        verifyNoInteractions(blogs);
    }

    @Test
    void rollsBackWhenReplyDeletionFails() {
        parentAndReply();
        doReturn(false).when(target).removeByIds(Collections.singletonList(5L));
        assertThrows(IllegalStateException.class, () -> service.deleteComment(4L));
        assertRolledBack();
        verify(target, never()).removeById(4L);
    }

    @SuppressWarnings("unchecked")
    private void failingCountUpdate() {
        UpdateChainWrapper<Blog> update = mock(UpdateChainWrapper.class, RETURNS_SELF);
        when(blogs.update()).thenReturn(update);
        when(update.update()).thenReturn(false);
    }

    @Test
    void rollsBackSavedCommentWhenCountUpdateFails() {
        when(blogs.getById(1L)).thenReturn(new Blog().setId(1L));
        doReturn(true).when(target).save(any(BlogComments.class));
        failingCountUpdate();
        BlogCommentSaveDTO request = new BlogCommentSaveDTO();
        request.setBlogId(1L);
        request.setContent("valid comment");
        assertThrows(IllegalStateException.class, () -> service.saveComment(request));
        assertRolledBack();
    }

    @Test
    void rollsBackDeletionWhenCountUpdateFails() {
        parentAndReply();
        doReturn(true).when(target).removeByIds(Collections.singletonList(5L));
        doReturn(true).when(target).removeById(4L);
        failingCountUpdate();
        assertThrows(IllegalStateException.class, () -> service.deleteComment(4L));
        assertRolledBack();
    }
}
