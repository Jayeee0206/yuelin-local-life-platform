package com.yuelin.controller;

import com.yuelin.dto.Result;
import com.yuelin.dto.UserDTO;
import com.yuelin.service.UploadAssetService;
import com.yuelin.service.UploadStorage;
import com.yuelin.utils.UserHolder;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UploadControllerTest {
    @TempDir Path root;
    @TempDir Path external;
    private UploadController controller;
    private UploadAssetService assets;
    private UploadStorage storage;

    @BeforeEach
    void setup() {
        assets = mock(UploadAssetService.class);
        storage = spy(new UploadStorage(root));
        controller = new UploadController();
        ReflectionTestUtils.setField(controller, "uploadAssetService", assets);
        ReflectionTestUtils.setField(controller, "uploadStorage", storage);
        UserDTO user = new UserDTO(); user.setId(9L); UserHolder.saveUser(user);
    }
    @AfterEach void clear() { UserHolder.removeUser(); }
    private String name() { return "blogs/0/1/" + UUID.randomUUID() + ".png"; }
    private MockMultipartFile png() {
        return new MockMultipartFile("file", "avatar.png", "image/png",
                new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A});
    }

    @Test void rejectsUnsupportedFileSuffix() {
        Result result = controller.uploadImage(new MockMultipartFile("file", "bad.exe", "image/png", new byte[]{1}));
        assertFalse(result.getSuccess()); assertTrue(result.getErrorMsg().contains("仅支持"));
        verifyNoInteractions(assets, storage);
    }
    @Test void rejectsNonImageContentType() {
        assertFalse(controller.uploadImage(new MockMultipartFile("file", "bad.jpg", "text/plain", new byte[]{1})).getSuccess());
    }
    @Test void rejectsSpoofedMagicBytes() {
        Result result = controller.uploadImage(new MockMultipartFile("file", "bad.jpg", "image/jpeg", new byte[]{1}));
        assertFalse(result.getSuccess()); assertTrue(result.getErrorMsg().contains("格式不匹配"));
    }
    @Test void rejectsOversizedImage() {
        Result result = controller.uploadImage(new MockMultipartFile("file", "big.jpg", "image/jpeg", new byte[5 * 1024 * 1024 + 1]));
        assertFalse(result.getSuccess()); assertTrue(result.getErrorMsg().contains("5MB"));
    }
    @Test void anonymousUploadNeverWritesFileOrMetadata() {
        UserHolder.removeUser();
        assertFalse(controller.uploadImage(png()).getSuccess());
        verifyNoInteractions(assets, storage);
    }
    @Test void storesBytesAndRegistersDurableOwnership() {
        Result result = controller.uploadImage(png());
        assertTrue(result.getSuccess());
        String path = UploadAssetService.canonical((String) result.getData());
        assertTrue(Files.exists(root.resolve(path)));
        verify(assets).reserve(path, 9L); verify(assets).ready(path, 9L);
    }
    @Test void unauthorizedDeletionNeverTouchesStorage() {
        String path = name();
        when(assets.beginDelete(path, 9L)).thenReturn(UploadAssetService.DeleteDecision.DENIED);
        assertFalse(controller.deleteBlogImg("/uploads/" + path).getSuccess());
        verifyNoInteractions(storage); verify(assets, never()).finishDelete(anyString(), anyLong());
    }
    @Test void ownerCanDeleteDraftAndRetryMissingFile() throws Exception {
        String path = name(); storage.write(path, png());
        when(assets.beginDelete(path, 9L)).thenReturn(UploadAssetService.DeleteDecision.DELETE);
        assertTrue(controller.deleteBlogImg("/uploads/" + path).getSuccess());
        assertFalse(Files.exists(root.resolve(path)));
        assertTrue(controller.deleteBlogImg("/" + path).getSuccess());
        verify(assets, times(2)).finishDelete(path, 9L);
    }
    @Test void completedDeletionDoesNotUnlinkAgain() {
        String path = name();
        when(assets.beginDelete(path, 9L)).thenReturn(UploadAssetService.DeleteDecision.ALREADY_DELETED);
        assertTrue(controller.deleteBlogImg(path).getSuccess()); verifyNoInteractions(storage);
    }
    @Test void rejectsTraversalAliasesAndInvalidNames() {
        for (String path : new String[]{"/uploads/../../outside.jpg", "../x.png", "//blogs/0/1/x.png", "blogs\\0\\1\\x.png", "/uploads/%2e%2e/x.png", ""}) {
            assertFalse(controller.deleteBlogImg(path).getSuccess());
        }
        verifyNoInteractions(assets, storage);
    }
    @Test void failedRegistrationNeverWritesBytes() {
        doThrow(new IllegalStateException("DB offline")).when(assets).reserve(anyString(), eq(9L));
        assertThrows(IllegalStateException.class, () -> controller.uploadImage(png()));
        verifyNoInteractions(storage);
    }
    @Test void ambiguousReadyCommitRetainsBytesForReconciliation() throws Exception {
        doThrow(new IllegalStateException("lost acknowledgement")).when(assets).ready(anyString(), eq(9L));
        assertThrows(IllegalStateException.class, () -> controller.uploadImage(png()));
        verify(storage, never()).delete(anyString()); verify(assets, never()).failed(anyString(), anyLong());
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            assertEquals(1L, paths.filter(Files::isRegularFile).count());
        }
    }
    @Test void filesystemDeletionFailureRetainsRetryableMetadata() throws Exception {
        String path = name();
        when(assets.beginDelete(path, 9L)).thenReturn(UploadAssetService.DeleteDecision.DELETE);
        doThrow(new java.io.IOException("disk error")).when(storage).delete(path);
        assertThrows(IllegalStateException.class, () -> controller.deleteBlogImg(path));
        verify(assets, never()).finishDelete(anyString(), anyLong());
    }
    @Test void refusesDirectoryDeletion() throws Exception {
        String path = name(); Files.createDirectories(root.resolve(path));
        assertThrows(java.io.IOException.class, () -> storage.delete(path));
        assertTrue(Files.isDirectory(root.resolve(path)));
    }
    @Test void neverOverwritesExistingUpload() throws Exception {
        String path = name(); storage.write(path, png());
        assertThrows(java.io.IOException.class, () -> storage.write(path, png()));
        assertTrue(Files.exists(root.resolve(path)));
    }
    @Test void symlinkedDirectoryCannotEscapeUploadRoot() throws Exception {
        Path outside = external;
        Path blogs = root.resolve("blogs");
        try { Files.createSymbolicLink(blogs, outside); }
        catch (java.io.IOException | UnsupportedOperationException unsupported) {
            Assumptions.assumeTrue(false, "OS does not permit symlink creation");
        }
        String path = name();
        assertThrows(java.io.IOException.class, () -> storage.write(path, png()));
        assertThrows(java.io.IOException.class, () -> storage.delete(path));
        assertFalse(Files.exists(outside.resolve("0/1")));
    }
}
