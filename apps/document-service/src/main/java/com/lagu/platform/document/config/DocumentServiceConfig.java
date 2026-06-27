package com.lagu.platform.document.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class DocumentServiceConfig {

    @Value("${platform.image-service.url:http://localhost:8200}")
    private String imageServiceUrl;

    @Bean
    public RestClient imageRestClient() {
        return RestClient.builder()
                .baseUrl(imageServiceUrl)
                .defaultHeader("X-Internal-Service", "document-service")
                .build();
    }
}
