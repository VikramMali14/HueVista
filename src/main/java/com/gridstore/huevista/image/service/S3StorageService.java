package com.gridstore.huevista.image.service;

import com.gridstore.huevista.common.exception.StorageException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;

@Slf4j
@Service
@Conditional(com.gridstore.huevista.image.config.S3EnabledCondition.class)
@RequiredArgsConstructor
public class S3StorageService implements StorageService {

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;

    @Value("${app.s3.bucket-name}")
    private String bucketName;

    @Value("${app.s3.presigned-url-expiry-minutes:60}")
    private int presignedUrlExpiryMinutes;

    @Override
    public String store(MultipartFile file, String userId) throws IOException {
        String extension = extractExtension(file.getOriginalFilename());
        String key = userId + "/" + UUID.randomUUID() + extension;

        log.debug("S3 putObject → bucket={}, key={}, endpoint={}",
                bucketName, key,
                s3Client.serviceClientConfiguration().endpointOverride()
                        .map(Object::toString).orElse("default (AWS)"));

        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(bucketName)
                        .key(key)
                        .contentType(file.getContentType())
                        .serverSideEncryption(ServerSideEncryption.AES256) // encrypt at rest
                        .build(),
                RequestBody.fromBytes(file.getBytes())
        );

        log.info("Stored image in S3: key={}", key);
        return key;
    }

    @Override
    public String store(byte[] bytes, String userId, String filename, String contentType) {
        String extension = extractExtension(filename);
        String key = userId + "/" + UUID.randomUUID() + extension;

        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(bucketName)
                        .key(key)
                        .contentType(contentType)
                        .serverSideEncryption(ServerSideEncryption.AES256)
                        .build(),
                RequestBody.fromBytes(bytes)
        );

        log.info("Stored bytes in S3: key={} size={}B contentType={}", key, bytes.length, contentType);
        return key;
    }

    @Override
    public byte[] load(String storageKey) {
        try {
            return s3Client.getObjectAsBytes(
                    GetObjectRequest.builder()
                            .bucket(bucketName)
                            .key(storageKey)
                            .build()
            ).asByteArray();
        } catch (Exception e) {
            throw new StorageException("Failed to load image from S3", e);
        }
    }

    @Override
    public void delete(String storageKey) {
        try {
            s3Client.deleteObject(
                    DeleteObjectRequest.builder()
                            .bucket(bucketName)
                            .key(storageKey)
                            .build()
            );
            log.info("Deleted image from S3: key={}", storageKey);
        } catch (Exception e) {
            throw new StorageException("Failed to delete image from S3", e);
        }
    }

    /**
     * Returns a presigned URL valid for `presignedUrlExpiryMinutes` (default 60 min).
     * The client fetches the image directly from S3 — zero bandwidth through our server.
     */
    @Override
    public String getPublicUrl(String storageKey) {
        PresignedGetObjectRequest presigned = s3Presigner.presignGetObject(r -> r
                .signatureDuration(Duration.ofMinutes(presignedUrlExpiryMinutes))
                .getObjectRequest(g -> g.bucket(bucketName).key(storageKey))
        );
        return presigned.url().toString();
    }

    /**
     * Returns a safe, normalised extension derived from the (untrusted) original
     * filename: only the characters after the final dot, reduced to lowercase
     * alphanumerics and capped in length, so a crafted filename can never inject
     * unexpected characters into the S3 object key.
     */
    private String extractExtension(String filename) {
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
