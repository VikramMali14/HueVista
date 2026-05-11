package com.gridstore.huevista.image.service;

import com.gridstore.huevista.common.exception.StorageException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
@ConditionalOnProperty(name = "app.s3.bucket-name")   // only active when bucket is configured
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
    public byte[] load(String storageKey) {
        try {
            return s3Client.getObjectAsBytes(
                    GetObjectRequest.builder()
                            .bucket(bucketName)
                            .key(storageKey)
                            .build()
            ).asByteArray();
        } catch (Exception e) {
            throw new StorageException("Failed to load image from S3: " + storageKey, e);
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
            throw new StorageException("Failed to delete image from S3: " + storageKey, e);
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

    private String extractExtension(String filename) {
        if (filename == null || !filename.contains(".")) return "";
        return filename.substring(filename.lastIndexOf('.'));
    }
}
