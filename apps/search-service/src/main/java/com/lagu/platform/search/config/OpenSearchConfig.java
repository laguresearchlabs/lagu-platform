package com.lagu.platform.search.config;

import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.opensearch.client.RestClient;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.transport.rest_client.RestClientTransport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The OpenSearch connection.
 *
 * <p>Scheme and credentials used to be absent here: the client was built with a hardcoded
 * {@code "http"} and no authentication, while the deployment supplied {@code OPENSEARCH_SCHEME},
 * {@code OPENSEARCH_USERNAME} and {@code OPENSEARCH_PASSWORD} that nothing read. That works against
 * a local container with the security plugin off, and cannot work against the production cluster,
 * which runs the plugin with generated TLS on the HTTP layer — every call would have been refused
 * before it reached an index.
 *
 * <p>All three default to the local values, so a developer stack still needs no configuration.
 */
@Configuration
public class OpenSearchConfig {

    @Value("${opensearch.host:localhost}")
    private String host;

    @Value("${opensearch.port:9200}")
    private int port;

    @Value("${opensearch.scheme:http}")
    private String scheme;

    @Value("${opensearch.username:}")
    private String username;

    @Value("${opensearch.password:}")
    private String password;

    /**
     * Exposed as its own bean because the typed client does not cover the ISM plugin endpoints
     * ({@code _plugins/_ism/...}), which are what apply a retention policy. See
     * {@code AnalyticsIndexInitializer}.
     */
    @Bean
    public RestClient openSearchRestClient() {
        var builder = RestClient.builder(new HttpHost(host, port, scheme));

        if (!username.isBlank()) {
            CredentialsProvider credentials = new BasicCredentialsProvider();
            credentials.setCredentials(AuthScope.ANY,
                    new UsernamePasswordCredentials(username, password));
            builder.setHttpClientConfigCallback(http -> http.setDefaultCredentialsProvider(credentials));
        }
        return builder.build();
    }

    @Bean
    public OpenSearchClient openSearchClient(RestClient openSearchRestClient) {
        return new OpenSearchClient(new RestClientTransport(openSearchRestClient, new JacksonJsonpMapper()));
    }
}
