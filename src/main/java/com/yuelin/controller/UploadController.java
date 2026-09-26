package com.yuelin.controller;

import cn.hutool.core.util.StrUtil;
import com.yuelin.dto.Result;
import com.yuelin.service.UploadAssetService;
import com.yuelin.service.UploadStorage;
import com.yuelin.dto.UserDTO;
import com.yuelin.utils.UserHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("upload")
public class UploadController {

    private static final long MAX_IMAGE_SIZE = 5L * 1024 * 1024;
    private static final Set<String> ALLOWED_IMAGE_SUFFIXES = Set.of("jpg", "jpeg", "png", "webp", "gif");

    @Resource
    private UploadAssetService uploadAssetService;

    @Resource
    private UploadStorage uploadStorage;

    @PostMapping("blog")
    public Result uploadImage(@RequestParam("file") MultipartFile image) {
        UserDTO currentUser = UserHolder.getUser();
        if (currentUser == null || currentUser.getId() == null) { return Result.fail("请先登录"); }
        String registered = null;
        boolean written = false;
        try {
            if (image == null) { return Result.fail("请选择图片"); }
            String originalFilename = image.getOriginalFilename();
            String suffix = originalFilename == null
                    ? ""
                    : StrUtil.subAfter(originalFilename, ".", true).toLowerCase(Locale.ROOT);
            String contentType = image.getContentType();
            if (image.isEmpty() || !ALLOWED_IMAGE_SUFFIXES.contains(suffix)
                    || contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("image/")) {
                return Result.fail("仅支持 jpg、jpeg、png、webp 或 gif 图片");
            }
            if (image.getSize() > MAX_IMAGE_SIZE) {
                return Result.fail("图片不能超过5MB");
            }
            String detectedType = detectImageType(image);
            if (!suffixMatches(suffix, detectedType)) {
                return Result.fail("文件内容与图片格式不匹配");
            }

            String fileName = createNewFileName(suffix);
            uploadAssetService.reserve(fileName, currentUser.getId());
            registered = fileName;
            uploadStorage.write(fileName, image);
            written = true;
            uploadAssetService.ready(fileName, currentUser.getId());
            return Result.ok("/" + fileName);
        } catch (IOException | RuntimeException uploadError) {
            if (registered != null && !written) {
                try { uploadAssetService.failed(registered, currentUser.getId()); }
                catch (RuntimeException metadataError) { uploadError.addSuppressed(metadataError); }
            }
            // If READY acknowledgement is ambiguous, retain bytes and metadata for reconciliation.
            // Deleting them here could remove an upload whose database commit already succeeded.
            throw new IllegalStateException("文件上传失败", uploadError);
        }
    }

    @DeleteMapping("blog")
    public Result deleteBlogImg(@RequestParam("name") String filename) {
        String path;
        try { path = UploadAssetService.canonical(filename); }
        catch (IllegalArgumentException invalid) { return Result.fail(invalid.getMessage()); }
        UserDTO user = UserHolder.getUser();
        if (user == null || user.getId() == null) { return Result.fail("请先登录"); }
        UploadAssetService.DeleteDecision decision = uploadAssetService.beginDelete(path, user.getId());
        if (decision == UploadAssetService.DeleteDecision.DENIED) { return Result.fail("无权删除该图片或图片已被使用"); }
        if (decision == UploadAssetService.DeleteDecision.ALREADY_DELETED) { return Result.ok(); }
        try {
            uploadStorage.delete(path);
            uploadAssetService.finishDelete(path, user.getId());
            return Result.ok();
        } catch (IOException error) {
            // DELETING is retained for retry; publication can no longer bind this asset.
            throw new IllegalStateException("图片删除失败，请重试", error);
        }
    }

    private String detectImageType(MultipartFile image) throws IOException {
        byte[] header;
        try (InputStream input = image.getInputStream()) {
            header = input.readNBytes(12);
        }
        if (startsWith(header, 0xFF, 0xD8, 0xFF)) {
            return "jpg";
        }
        if (startsWith(header, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) {
            return "png";
        }
        if (startsWith(header, 'G', 'I', 'F', '8', '7', 'a')
                || startsWith(header, 'G', 'I', 'F', '8', '9', 'a')) {
            return "gif";
        }
        if (header.length >= 12 && startsWith(header, 'R', 'I', 'F', 'F')
                && header[8] == 'W' && header[9] == 'E' && header[10] == 'B' && header[11] == 'P') {
            return "webp";
        }
        return "";
    }

    private boolean startsWith(byte[] bytes, int... signature) {
        if (bytes.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if ((bytes[i] & 0xFF) != signature[i]) {
                return false;
            }
        }
        return true;
    }

    private boolean suffixMatches(String suffix, String detectedType) {
        return suffix.equals(detectedType)
                || ((suffix.equals("jpg") || suffix.equals("jpeg")) && detectedType.equals("jpg"));
    }

    private String createNewFileName(String suffix) {
        String name = UUID.randomUUID().toString();
        int hash = name.hashCode();
        int d1 = hash & 0xF;
        int d2 = (hash >> 4) & 0xF;
        return StrUtil.format("blogs/{}/{}/{}.{}", d1, d2, name, suffix);
    }
}
