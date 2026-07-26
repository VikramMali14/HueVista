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

    /**
     * Deletes every stored object and returns how many went.
     *
     * Only for the admin platform reset, which empties the tables that hold storage
     * keys — after it there is nothing left that could name a file, so anything still
     * in storage is unreachable by definition. Purging wholesale rather than walking
     * the keys is also the only complete option: mask keys are spread across
     * {@code regions.mask_url} and {@code mask_data} mixed in with presigned URLs,
     * share paths and legacy JSON, so a key-by-key sweep would strand exactly the
     * files it failed to parse.
     *
     * Best-effort per object: one failure must not abort the rest.
     */
    int deleteAll();
}
