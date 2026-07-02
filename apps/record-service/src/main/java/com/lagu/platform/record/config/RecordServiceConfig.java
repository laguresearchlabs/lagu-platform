package com.lagu.platform.record.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lagu.platform.common.cache.JacksonRedisSerializer;
import com.lagu.platform.record.client.MetadataClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
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

    // Identity used by record-service itself when calling other services — mirrors
    // search-service's MetadataClient/RecordClient. GatewayHeaderFilter rejects requests with no
    // X-User-Id/X-Platform-Gateway-Secret as unauthenticated, so without these headers every
    // schema-registry/image-service call from here 401s.
    private static final String SYSTEM_USER_ID = "00000000-0000-0000-0000-000000000001";

    @Bean
    @LoadBalanced
    public RestClient.Builder loadBalancedRestClientBuilder() {
        return RestClient.builder();
    }

    @Bean
    public RestClient schemaRegistryRestClient(
            RestClient.Builder loadBalancedRestClientBuilder,
            @Value("${platform.gateway.shared-secret:CHANGE_ME_INSECURE_DEFAULT_SECRET_ROTATE_IN_PROD}")
            String gatewaySharedSecret) {
        return loadBalancedRestClientBuilder.clone()
                .baseUrl("http://schema-registry")
                .defaultHeader("X-Internal-Service", "record-service")
                .defaultHeader("X-Platform-Gateway-Secret", gatewaySharedSecret)
                .defaultHeader("X-User-Id", SYSTEM_USER_ID)
                .defaultHeader("X-User-Roles", "PLATFORM_ADMIN")
                .build();
    }

    @Bean
    public RestClient imageRestClient(
            RestClient.Builder loadBalancedRestClientBuilder,
            @Value("${platform.gateway.shared-secret:CHANGE_ME_INSECURE_DEFAULT_SECRET_ROTATE_IN_PROD}")
            String gatewaySharedSecret) {
        return loadBalancedRestClientBuilder.clone()
                .baseUrl("http://image-service")
                .defaultHeader("X-Internal-Service", "record-service")
                .defaultHeader("X-Platform-Gateway-Secret", gatewaySharedSecret)
                .defaultHeader("X-User-Id", SYSTEM_USER_ID)
                .defaultHeader("X-User-Roles", "PLATFORM_ADMIN")
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
