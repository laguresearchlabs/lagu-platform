package com.lagu.platform.record.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lagu.platform.common.cache.JacksonRedisSerializer;
import com.lagu.platform.record.client.MetadataClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;

@Configuration
public class RecordServiceConfig {

    @Value("${platform.metadata-service.url:http://localhost:8100}")
    private String metadataServiceUrl;

    @Value("${platform.image-service.url:http://localhost:8200}")
    private String imageServiceUrl;

    @Bean
    public RestClient metadataRestClient() {
        return RestClient.builder()
                .baseUrl(metadataServiceUrl)
                .defaultHeader("X-Internal-Service", "record-service")
                .build();
    }

    @Bean
    public RestClient imageRestClient() {
        return RestClient.builder()
                .baseUrl(imageServiceUrl)
                .defaultHeader("X-Internal-Service", "record-service")
                .build();
    }

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory factory) {
        JacksonRedisSerializer serializer = new JacksonRedisSerializer();

        RedisCacheConfiguration defaults = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(5))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(serializer));

        return RedisCacheManager.builder(factory)
                .cacheDefaults(defaults)
                .withInitialCacheConfigurations(Map.of(
                        MetadataClient.SCHEMA_CACHE, defaults.entryTtl(Duration.ofMinutes(10))
                ))
                .build();
    }
}
