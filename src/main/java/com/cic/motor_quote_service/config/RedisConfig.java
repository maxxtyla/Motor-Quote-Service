package com.cic.motor_quote_service.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.spring.data.connection.RedissonConnectionFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.Map;

/**
 * Redis and Redisson configuration for CIC Motor Quote Service.
 *
 * TWO USES OF REDIS HERE:
 *
 *   1. CACHING via Spring Cache (@Cacheable / @CacheEvict)
 *      - Quote lookups by quoteNumber
 *      - AppUser/policyholder lookups (called frequently during payment)
 *      - Uses RedisCacheManager with per-cache TTLs
 *
 *   2. DISTRIBUTED LOCKING via Redisson RLock
 *      - M-Pesa callback deduplication
 *      - Prevents two Kubernetes pods processing the same receipt simultaneously
 *      - Uses Redisson's RedLock algorithm (safe across Redis cluster)
 *
 * WHY REDISSON over Lettuce/Jedis for locking:
 *   Lettuce and Jedis don't support distributed locks out of the box.
 *   You'd have to implement SET NX PX manually and handle lease renewal.
 *   Redisson's RLock is battle-tested: it auto-renews the lock TTL (watchdog)
 *   as long as the thread holds it, and releases cleanly on JVM crash.
 *
 * INTERN NOTE: Redisson connects to Redis over the same port (6379).
 * We use SingleServer mode — switch to ClusterServersConfig in production
 * when Redis runs as a cluster across 3 Kubernetes pods.
 */
@Configuration
@EnableCaching
public class RedisConfig {

    @Value("${spring.data.redis.host:localhost}")
    private String redisHost;

    @Value("${spring.data.redis.port:6379}")
    private int redisPort;

    @Value("${spring.data.redis.password:}")
    private String redisPassword;

    // ── Redisson Client (for distributed locking) ─────────────────────────────

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + redisHost + ":" + redisPort)
                .setPassword(redisPassword.isBlank() ? null : redisPassword)
                .setConnectionMinimumIdleSize(2)
                .setConnectionPoolSize(10)
                .setConnectTimeout(3_000)
                .setTimeout(3_000)
                .setRetryAttempts(3)
                .setRetryInterval(1_500);
        return Redisson.create(config);
    }

    // ── Spring Redis Connection (for Spring Cache / RedisTemplate) ────────────

    @Bean
    public RedisConnectionFactory redisConnectionFactory(RedissonClient redissonClient) {
        // Use Redisson as the Spring Data Redis connection factory.
        // This means one Redis connection pool shared between locking and caching.
        return new RedissonConnectionFactory(redissonClient);
    }

    // ── Spring CacheManager with per-cache TTLs ───────────────────────────────

    @Bean
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        // Default config — used unless overridden in the cache-specific map below
        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(10))       // 10-minute default TTL
                .disableCachingNullValues()              // Don't cache null (DB misses)
                .serializeKeysWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(
                                new StringRedisSerializer()))
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(
                                new GenericJackson2JsonRedisSerializer()));

        // Per-cache TTL overrides:
        Map<String, RedisCacheConfiguration> cacheConfigs = Map.of(

                // Quotes don't change often — cache for 15 minutes
                "quotes",       defaultConfig.entryTtl(Duration.ofMinutes(15)),

                // AppUser/policyholder data is mostly static after creation — cache longer
                "policyholders", defaultConfig.entryTtl(Duration.ofMinutes(30)),

                // Payment status changes frequently — short TTL
                "payments",     defaultConfig.entryTtl(Duration.ofMinutes(2)),

                // @PreAuthorize security checks use this — keep short for account lock propagation
                "users",        defaultConfig.entryTtl(Duration.ofMinutes(5))
        );

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultConfig)
                .withInitialCacheConfigurations(cacheConfigs)
                .build();
    }
}
