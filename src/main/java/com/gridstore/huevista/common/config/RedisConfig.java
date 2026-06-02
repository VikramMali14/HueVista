package com.gridstore.huevista.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

import java.time.Duration;

/**
 * Wires the Redis-backed cache manager. Skipped when {@code spring.cache.type=none}
 * (used in tests) so the app can run / be tested without a Redis instance — the
 * {@code @Cacheable} annotations then resolve against Spring's NoOp cache.
 */
@Configuration
@EnableCaching
@ConditionalOnProperty(name = "spring.cache.type", havingValue = "redis", matchIfMissing = true)
public class RedisConfig {

    @Value("${app.cache.shade-ttl-minutes:60}")
    private long shadeTtlMinutes;

    @Bean
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        // JSON values instead of JDK serialization: cached payloads need not be Serializable,
        // and stored entries are human-readable when inspecting Redis directly.
        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(shadeTtlMinutes))
                .disableCachingNullValues()
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(
                        GenericJacksonJsonRedisSerializer.builder().enableUnsafeDefaultTyping().build()));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultConfig)
                .withCacheConfiguration("shades", defaultConfig.entryTtl(Duration.ofMinutes(shadeTtlMinutes)))
                .withCacheConfiguration("shade-families", defaultConfig.entryTtl(Duration.ofHours(6)))
                .withCacheConfiguration("shade-detail", defaultConfig.entryTtl(Duration.ofHours(6)))
                .build();
    }

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }
}
