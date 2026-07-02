package com.lagu.platform.document.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

@Configuration
public class DocumentServiceConfig {

    @Bean
    @LoadBalanced
    public RestClient.Builder loadBalancedRestClientBuilder() {
        return RestClient.builder();
    }

    // Eureka's own RestClientEurekaHttpClient auto-configuration autowires *any* unqualified
    // RestClient.Builder bean it finds for its own registration/heartbeat calls to the Eureka
    // server — including the @LoadBalanced one above, which then tries to load-balance requests
    // to the literal Eureka server host as if it were a service ID, breaking heartbeats entirely
    // (see https://github.com/spring-cloud/spring-cloud-netflix/issues/4382, unresolved as of
    // Spring Cloud Netflix's current release). @Primary steers that unqualified lookup to this
    // plain builder instead; imageRestClient below uses @Qualifier to stay pinned to the
    // load-balanced one regardless of which bean is primary.
    @Bean
    @Primary
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }

    // DocumentTypeRegistry uses RestTemplate (not RestClient) — @LoadBalanced attaches
    // the load-balancer interceptor directly to this bean.
    @Bean
    @LoadBalanced
    public RestTemplate loadBalancedRestTemplate() {
        return new RestTemplate();
    }

    @Bean
    public RestClient imageRestClient(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder loadBalancedRestClientBuilder) {
        return loadBalancedRestClientBuilder.clone()
                .baseUrl("http://image-service")
                .defaultHeader("X-Internal-Service", "document-service")
                .build();
    }
}
