package com.yuelin.service;

import com.yuelin.utils.SystemConstants;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/** Dedicated app-owned directory required; never recursively delete paths supplied by clients. */
@Component
public class UploadStorage {
    private static final long MAX_BYTES = 5L * 1024 * 1024;
    private final Path root;
    public UploadStorage() { this(Paths.get(SystemConstants.IMAGE_UPLOAD_DIR)); }
    public UploadStorage(Path root) { this.root = root.toAbsolutePath().normalize(); }

    public void write(String name, MultipartFile file) throws IOException {
        Path target = resolve(name);
        Path parent = target.getParent();
        if (parent == null) { throw new IOException("Invalid upload parent directory"); }
        Files.createDirectories(parent);
        target = resolve(name);
        boolean created = false;
        try (InputStream input = file.getInputStream()) {
            try (OutputStream output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                created = true;
                byte[] buffer = new byte[8192];
                long size = 0;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    size += read;
                    if (size > MAX_BYTES) { throw new IOException("Image exceeds size limit"); }
                    output.write(buffer, 0, read);
                }
            }
        } catch (IOException | RuntimeException error) {
            if (created) {
                try { Files.deleteIfExists(target); }
                catch (IOException cleanup) { error.addSuppressed(cleanup); }
            }
            throw error;
        }
    }

    public void delete(String name) throws IOException {
        Path target = resolve(name);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Refusing to delete a non-regular upload");
        }
        Files.deleteIfExists(target); // Retry after a previous successful unlink is safe.
    }

    private Path resolve(String name) throws IOException {
        Path target = root.resolve(UploadAssetService.canonical(name));
        for (Path path = target; path != null; path = path.getParent()) {
            if (Files.isSymbolicLink(path)) { throw new IOException("Symbolic links are not allowed in upload paths"); }
        }
        return target;
    }
}
