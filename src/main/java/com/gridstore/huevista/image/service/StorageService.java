package com.gridstore.huevista.image.service;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

public interface StorageService {
    String store(MultipartFile file, String userId) throws IOException;

    /**
     * Stores raw bytes (e.g. a generated mask PNG) and returns the storage key.
     * The filename is used to pick an extension; the contentType is stored as
     * S3 object metadata.
     */
    String store(byte[] bytes, String userId, String filename, String contentType) throws IOException;

    byte[] load(String storageKey) throws IOException;
    void delete(String storageKey);
    String getPublicUrl(String storageKey);
}
