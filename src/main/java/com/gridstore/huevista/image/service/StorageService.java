package com.gridstore.huevista.image.service;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

public interface StorageService {
    String store(MultipartFile file, String userId) throws IOException;
    byte[] load(String storageKey) throws IOException;
    void delete(String storageKey);
    String getPublicUrl(String storageKey);
}
