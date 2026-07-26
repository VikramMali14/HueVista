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
import java.util.List;
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
     * Empties the bucket, 1000 keys per request (the DeleteObjects maximum), following
     * the continuation token so a bucket larger than one page is fully cleared.
     *
     * Every object lives at {@code <userId>/<uuid>.<ext>} with no shared prefix, so the
     * bucket holds this application's uploads and nothing else — there is no subset to
     * spare. Failures are logged and counted out rather than thrown: a reset that
     * cleared the database must not appear to have failed because one object was
     * already gone.
     */
    @Override
    public int deleteAll() {
        int deleted = 0;
        String continuationToken = null;
        try {
            do {
                ListObjectsV2Request.Builder list = ListObjectsV2Request.builder().bucket(bucketName);
                if (continuationToken != null) list.continuationToken(continuationToken);
                ListObjectsV2Response page = s3Client.listObjectsV2(list.build());

                List<ObjectIdentifier> batch = page.contents().stream()
                        .map(o -> ObjectIdentifier.builder().key(o.key()).build())
                        .toList();
                if (!batch.isEmpty()) {
                    DeleteObjectsResponse result = s3Client.deleteObjects(DeleteObjectsRequest.builder()
                            .bucket(bucketName)
                            .delete(Delete.builder().objects(batch).quiet(true).build())
                            .build());
                    deleted += batch.size() - result.errors().size();
                    result.errors().forEach(e ->
                            log.warn("[admin] could not delete S3 object {}: {}", e.key(), e.message()));
                }
                continuationToken = Boolean.TRUE.equals(page.isTruncated()) ? page.nextContinuationToken() : null;
            } while (continuationToken != null);
        } catch (Exception e) {
            log.error("[admin] S3 purge stopped after {} object(s): {}", deleted, e.getMessage());
        }
        log.warn("[admin] purged {} object(s) from S3 bucket {}", deleted, bucketName);
        return deleted;
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
