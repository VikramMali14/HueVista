package com.gridstore.huevista.image.controller;

import com.gridstore.huevista.image.dto.StorageOriginResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.TimeUnit;

/**
 * Where this deployment's images physically live — the one fact the frontend's
 * {@code /api/media} passthrough needs and could not get.
 *
 * <p><b>Why an endpoint and not an environment variable.</b> The frontend already had
 * one: set {@code S3_BUCKET_NAME} on the web container to the same value the API uses,
 * and its proxy arms itself. In production nobody did — there is nothing about the web
 * container that suggests it needs the name of the API's bucket — so the route answered
 * {@code 503 Media proxy is not configured} for every image, the canvas fallback had
 * nowhere to fall back to, and building a PDF from an AI image failed with "could not
 * read that image on this device" on a page where the picture was plainly visible.
 *
 * <p>A value that has to be copied into a second place to work is a value that will be
 * wrong. This makes the API the single source of truth: the same property the storage
 * service presigns from is the one the frontend reads, so a deployment that can upload
 * an image can always display it, and moving buckets is one variable rather than two.
 *
 * <p><b>Why it is public.</b> It discloses the bucket and its region, and both are
 * already written in full in every presigned URL the API hands out — including on the
 * public share page, which by design is read by people with no account. There is
 * nothing here a reader could not copy out of an image tag. Making it authenticated
 * would, in exchange for that non-secret, mean the frontend's server had to hold a
 * session to configure itself, which is worse. Nothing is enumerable: it names the one
 * bucket this application writes to and offers no way to ask about another.
 *
 * <p>It is deliberately not a URL. Handing a caller a fully-formed origin invites them
 * to fetch whatever string arrives; the parts come separately so the reader validates
 * them and builds the origin itself, which is exactly what the frontend does before it
 * will connect to anything.
 */
@RestController
@RequestMapping("/api/images")
@Tag(name = "Images", description = "Upload and manage room/exterior photos")
public class StorageOriginController {

    /** Blank whenever S3 is off and images are served from local disk instead. */
    private final String bucketName;

    private final String region;

    public StorageOriginController(
            @Value("${app.s3.bucket-name:}") String bucketName,
            @Value("${app.s3.region:ap-south-1}") String region) {
        this.bucketName = bucketName;
        this.region = region;
    }

    @Operation(summary = "Where image bytes are stored",
            description = "Names the S3 bucket and region this deployment presigns image URLs "
                    + "from, so a same-origin image proxy can be configured from the API rather "
                    + "than from a second copy of the same setting. Answers `provider: \"local\"` "
                    + "with no bucket when S3 is not in use.")
    @GetMapping("/storage")
    public ResponseEntity<StorageOriginResponse> storage() {
        String bucket = bucketName == null ? "" : bucketName.trim();
        StorageOriginResponse body = bucket.isEmpty()
                ? StorageOriginResponse.local()
                : StorageOriginResponse.s3(bucket, region == null ? "" : region.trim());
        // Configuration, not content: it changes when the deployment does. Cached long
        // enough that a frontend restarting under load does not ask on every image, and
        // short enough that a bucket move is picked up without a coordinated restart.
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(10, TimeUnit.MINUTES).cachePublic())
                .body(body);
    }
}
