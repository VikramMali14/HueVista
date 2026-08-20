package com.gridstore.huevista.image.dto;

/**
 * The storage backing this deployment's images, in the parts a caller needs to build
 * an S3 virtual-hosted origin — and no more than that.
 *
 * <p>The bucket and region travel separately rather than as a ready-made URL on
 * purpose. The one consumer is a server-side image passthrough deciding what it is
 * willing to connect to, and a proxy that fetches whatever origin string it was handed
 * has surrendered the only check that makes it safe. Sending the pieces forces it to
 * validate them and assemble the host itself, which is what it should be doing anyway.
 *
 * @param provider {@code "s3"} when images are presigned out of a bucket, {@code "local"}
 *                 when they are served from the API's own disk and no proxy is needed.
 * @param bucket   the bucket name, or null on local storage.
 * @param region   the bucket's AWS region, or null on local storage.
 */
public record StorageOriginResponse(String provider, String bucket, String region) {

    public static StorageOriginResponse local() {
        return new StorageOriginResponse("local", null, null);
    }

    public static StorageOriginResponse s3(String bucket, String region) {
        return new StorageOriginResponse("s3", bucket, region);
    }
}
