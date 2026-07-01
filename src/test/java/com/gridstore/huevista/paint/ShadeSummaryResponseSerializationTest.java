package com.gridstore.huevista.paint;

import com.gridstore.huevista.paint.dto.ShadeSummaryResponse;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.JdkSerializationRedisSerializer;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /api/shades} is {@code @Cacheable} into the Redis "shades" cache, which serializes
 * values with JDK serialization. This guards that {@link ShadeSummaryResponse} (and a list of
 * them) round-trips back to the same type — i.e. it stays {@link java.io.Serializable} and the
 * controller keeps returning the payload directly rather than a non-Serializable wrapper.
 */
class ShadeSummaryResponseSerializationTest {

    private final JdkSerializationRedisSerializer serializer = new JdkSerializationRedisSerializer();

    private static ShadeSummaryResponse sampleShade() {
        return ShadeSummaryResponse.builder()
                .brandName("Asian Paints").brandSlug("asian-paints")
                .shadeCode("0001").name("Bone China").hexCode("#F3EEE4")
                .shadeFamily("off whites").popularity(1)
                .lrv(new BigDecimal("88.40")).rgbR(243).rgbG(238).rgbB(228)
                .finishRecommendations(List.of("Matt", "Satin"))
                .build();
    }

    @Test
    void singleShade_roundTripsToSameType() {
        ShadeSummaryResponse original = sampleShade();
        byte[] bytes = serializer.serialize(original);
        Object back = serializer.deserialize(bytes);

        assertThat(back).isInstanceOf(ShadeSummaryResponse.class).isEqualTo(original);
    }

    @Test
    void listOfShades_roundTripsToSameType() {
        List<ShadeSummaryResponse> original = List.of(sampleShade());
        byte[] bytes = serializer.serialize(original);
        Object back = serializer.deserialize(bytes);

        assertThat(back).isEqualTo(original);
        @SuppressWarnings("unchecked")
        List<Object> list = (List<Object>) back;
        assertThat(list).first().isInstanceOf(ShadeSummaryResponse.class);
    }
}
