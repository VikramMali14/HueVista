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
            throw new StorageException("Failed to delete file: " + storageKey, e);
        }
    }

    @Override
    public String getPublicUrl(String storageKey) {
        return "/api/images/files/" + storageKey;
    }

    private String extractExtension(String filename) {
        if (filename == null || !filename.contains(".")) return "";
        return filename.substring(filename.lastIndexOf('.'));
    }
}
