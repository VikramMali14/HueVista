package com.gridstore.huevista.image.service;

import com.gridstore.huevista.common.exception.StorageException;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

public class LocalStorageService implements StorageService {

    private final String storagePath;

    public LocalStorageService(String storagePath) {
        this.storagePath = storagePath;
    }

    @Override
    public String store(MultipartFile file, String userId) throws IOException {
        String extension = extractExtension(file.getOriginalFilename());
        String storageKey = userId + "/" + UUID.randomUUID() + extension;
        Path target = Path.of(storagePath, storageKey);
        Files.createDirectories(target.getParent());
        Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
        return storageKey;
    }

    @Override
    public String store(byte[] bytes, String userId, String filename, String contentType) throws IOException {
        String extension = extractExtension(filename);
        String storageKey = userId + "/" + UUID.randomUUID() + extension;
        Path target = Path.of(storagePath, storageKey);
        Files.createDirectories(target.getParent());
        Files.write(target, bytes);
        return storageKey;
    }

    @Override
    public byte[] load(String storageKey) throws IOException {
        return Files.readAllBytes(Path.of(storagePath, storageKey));
    }

    @Override
    public void delete(String storageKey) {
        try {
            Files.deleteIfExists(Path.of(storagePath, storageKey));
        } catch (IOException e) {
            throw new StorageException("Failed to delete file", e);
        }
    }

    @Override
    public String getPublicUrl(String storageKey) {
        return "/api/images/files/" + storageKey;
    }

    /**
     * Returns a safe, normalised extension derived from the (untrusted) original
     * filename: only the characters after the final dot, reduced to lowercase
     * alphanumerics and capped in length. This guarantees a crafted filename can
     * never inject a path separator, "..", or other junk into the storage key.
     */
    static String extractExtension(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) return "";
        String raw = filename.substring(dot + 1).toLowerCase();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length() && sb.length() < 5; i++) {
            char c = raw.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) sb.append(c);
        }
        return sb.length() == 0 ? "" : "." + sb;
    }
}
