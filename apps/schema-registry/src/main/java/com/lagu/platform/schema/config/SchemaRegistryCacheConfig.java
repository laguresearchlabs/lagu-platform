package com.lagu.platform.schema.config;

import com.lagu.platform.common.cache.JacksonRedisSerializer;
import com.lagu.platform.schema.service.ListingTypeService;
import com.lagu.platform.schema.service.TierConfigService;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.RedisSerializationContext;

import java.time.Duration;
import java.util.Map;

@Configuration
@EnableCaching
public class SchemaRegistryCacheConfig {

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory factory) {
        JacksonRedisSerializer serializer = new JacksonRedisSerializer();

        RedisCacheConfiguration defaults = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(5))
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(serializer));

        RedisCacheConfiguration schemaConfig = defaults.entryTtl(Duration.ofMinutes(5));
        RedisCacheConfiguration tierConfig   = defaults.entryTtl(Duration.ofMinutes(10));

        return RedisCacheManager.builder(factory)
                .cacheDefaults(defaults)
                .withInitialCacheConfigurations(Map.of(
                        ListingTypeService.CACHE_SCHEMA, schemaConfig,
                        TierConfigService.CACHE_TIER_CONFIG, tierConfig
                ))
                .build();
    }
}
