package com.lagu.platform.automation.service;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.functions.CheckedRunnable;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Map;

/**
 * Executes outbound webhook HTTP calls with per-hostname circuit breakers and retries.
 *
 * Circuit breaker is keyed per webhook hostname so that a single failing external
 * system opens only its own circuit and leaves calls to other hosts unaffected.
 *
 * Decoration order: Retry(CircuitBreaker(httpCall))
 *   → each retry attempt is independently tracked by the circuit breaker
 *   → if the circuit opens mid-retry, CallNotPermittedException is thrown and
 *     is NOT retried (it's in ignoreExceptions)
 */
@Component
@Slf4j
public class WebhookExecutor {

    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final RetryRegistry          retryRegistry;

    @Value("${platform.automation.webhook-timeout-seconds:10}")
    private int defaultTimeoutSeconds;

    public WebhookExecutor() {
        CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(50.0f)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(2)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .build();
        this.circuitBreakerRegistry = CircuitBreakerRegistry.of(cbConfig);

        RetryConfig retryConfig = RetryConfig.custom()
                .maxAttempts(3)
                .intervalFunction(IntervalFunction.ofExponentialBackoff(Duration.ofSeconds(1), 2.0))
                .retryExceptions(IOException.class, ResourceAccessException.class)
                .ignoreExceptions(CallNotPermittedException.class)
                .build();
        this.retryRegistry = RetryRegistry.of(retryConfig);
    }

    /**
     * Executes the HTTP call with retry + circuit breaker.
     *
     * @throws RuntimeException wrapping the root cause on permanent failure
     * @throws RuntimeException with "circuit breaker open" message when CB is open
     */
    public void execute(String url, String method,
                        Map<String, Object> body,
                        Map<String, Object> resolvedHeaders,
                        int timeoutSeconds) {

        String hostname = extractHostname(url);
        String key      = "webhook." + hostname;

        CircuitBreaker cb    = circuitBreakerRegistry.circuitBreaker(key);
        Retry          retry = retryRegistry.retry(key);

        // Inner call: build a fresh RestClient per invocation (no shared state).
        // Retry(CircuitBreaker(httpCall)): each retry attempt is tracked by the CB.
        CheckedRunnable cbCall    = CircuitBreaker.decorateCheckedRunnable(cb,
                () -> doHttpRequest(url, method, body, resolvedHeaders));
        CheckedRunnable decorated = Retry.decorateCheckedRunnable(retry, cbCall);

        log.debug("Calling webhook {} {} (CB state={})", method, url, cb.getState());
        try {
            decorated.run();
            log.info("Webhook succeeded: {} {} [CB={}]", method, url, cb.getState());
        } catch (CallNotPermittedException e) {
            log.warn("Webhook circuit breaker OPEN for host '{}' — call short-circuited", hostname);
            throw new RuntimeException("Webhook circuit breaker open for " + hostname
                    + " — too many recent failures. Will retry after 30s.", e);
        } catch (Throwable t) {
            log.error("Webhook failed after retries: {} {}", method, url, t);
            throw new RuntimeException("Webhook call failed for " + url + ": " + t.getMessage(), t);
        }
    }

    /** Current circuit breaker state for a given hostname — exposed for actuator / tests. */
    public CircuitBreaker.State circuitState(String hostname) {
        return circuitBreakerRegistry.circuitBreaker("webhook." + hostname).getState();
    }

    // ── private ───────────────────────────────────────────────────────────────

    private void doHttpRequest(String url, String method,
                                Map<String, Object> body,
                                Map<String, Object> headers) {
        RestClient.Builder builder = RestClient.builder().baseUrl(url);
        if (headers != null) {
            headers.forEach((k, v) -> builder.defaultHeader(k, String.valueOf(v)));
        }
        RestClient client = builder.build();

        boolean hasBody = "POST".equalsIgnoreCase(method)
                || "PUT".equalsIgnoreCase(method)
                || "PATCH".equalsIgnoreCase(method);

        if (hasBody) {
            client.method(HttpMethod.valueOf(method.toUpperCase()))
                    .uri("")
                    .body(body != null ? body : Map.of())
                    .retrieve()
                    .toBodilessEntity();
        } else {
            client.method(HttpMethod.valueOf(method.toUpperCase()))
                    .uri("")
                    .retrieve()
                    .toBodilessEntity();
        }
    }

    private String extractHostname(String url) {
        try {
            String host = new URI(url).getHost();
            return host != null ? host : sanitize(url);
        } catch (Exception e) {
            return sanitize(url);
        }
    }

    private String sanitize(String raw) {
        return raw.substring(0, Math.min(raw.length(), 50))
                  .replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
