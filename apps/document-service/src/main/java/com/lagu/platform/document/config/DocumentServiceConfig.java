package com.lagu.platform.document.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.ClientHttpRequestInterceptor;
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
    // the load-balancer interceptor directly to this bean. Also needs the same
    // X-Internal-Service/X-Platform-Gateway-Secret trust headers as imageRestClient below —
    // without them, schema-registry's GatewayHeaderFilter treats the call as unauthenticated
    // and DocumentTypeRegistry.refresh() gets a 401, silently falling back to the hardcoded
    // generic/HR document-type list forever (this is what happened here).
    @Bean
    @LoadBalanced
    public RestTemplate loadBalancedRestTemplate(
            @Value("${platform.gateway.shared-secret:CHANGE_ME_INSECURE_DEFAULT_SECRET_ROTATE_IN_PROD}")
            String gatewaySharedSecret) {
        RestTemplate template = new RestTemplate();
        ClientHttpRequestInterceptor internalAuth = (request, body, execution) -> {
            request.getHeaders().set("X-Internal-Service", "document-service");
            request.getHeaders().set("X-Platform-Gateway-Secret", gatewaySharedSecret);
            return execution.execute(request, body);
        };
        template.getInterceptors().add(internalAuth);
        return template;
    }

    // imageRestClient is gone. File storage no longer goes through image-service — or through
    // this JVM at all: clients PUT straight to the bucket using a presigned URL minted by
    // libs/storage. See DocumentService.requestUploadUrl.
}
