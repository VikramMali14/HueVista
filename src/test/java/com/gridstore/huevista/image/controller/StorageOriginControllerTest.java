package com.gridstore.huevista.image.controller;

import com.gridstore.huevista.image.dto.StorageOriginResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one answer the frontend's image passthrough configures itself from.
 *
 * The bug this closes was not in the proxy's logic — that was correct and well tested —
 * but in the fact that it needed the bucket name to be typed into a second deployment
 * and, in production, nobody had. So what matters here is that the API reports its own
 * storage truthfully, including the "there is no bucket" case: a deployment on local
 * disk must not have a bucket invented for it, or the frontend would arm a proxy
 * pointing at a host that does not exist.
 */
class StorageOriginControllerTest {

    @Test
    @DisplayName("names the bucket and region this deployment presigns from")
    void reportsS3() {
        StorageOriginResponse body =
                new StorageOriginController("image-storage-original", "ap-south-1")
                        .storage().getBody();

        assertThat(body).isNotNull();
        assertThat(body.provider()).isEqualTo("s3");
        assertThat(body.bucket()).isEqualTo("image-storage-original");
        assertThat(body.region()).isEqualTo("ap-south-1");
    }

    @Test
    @DisplayName("a deployment with no bucket reports local storage, not an empty bucket")
    void reportsLocalWhenUnset() {
        // app.s3.bucket-name defaults to the empty string, which is how S3 is switched
        // off (see S3EnabledCondition). Reporting `bucket: ""` here would have the
        // frontend build `https://.s3.ap-south-1.amazonaws.com` and try to fetch it.
        for (String unset : new String[] {"", "   ", null}) {
            StorageOriginResponse body =
                    new StorageOriginController(unset, "ap-south-1").storage().getBody();

            assertThat(body).isNotNull();
            assertThat(body.provider()).isEqualTo("local");
            assertThat(body.bucket()).isNull();
            assertThat(body.region()).isNull();
        }
    }

    @Test
    @DisplayName("surrounding whitespace never reaches the origin the caller builds")
    void trimsConfiguredValues() {
        StorageOriginResponse body =
                new StorageOriginController("  image-storage-original ", " ap-south-1 ")
                        .storage().getBody();

        assertThat(body).isNotNull();
        assertThat(body.bucket()).isEqualTo("image-storage-original");
        assertThat(body.region()).isEqualTo("ap-south-1");
    }
}
