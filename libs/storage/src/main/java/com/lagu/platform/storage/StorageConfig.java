package com.lagu.platform.storage;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.io.FileInputStream;
import java.io.IOException;

/**
 * Wires whichever backend {@code platform.storage.provider} selects.
 *
 * <p>Consuming services pick this up by adding {@code com.lagu.platform.storage} to their
 * {@code scanBasePackages}/{@code @ComponentScan}, the same way they already pick up
 * {@code com.lagu.platform.security}.
 */
@Configuration
@EnableConfigurationProperties(StorageProperties.class)
@Slf4j
public class StorageConfig {

    /**
     * The real scanner when {@code platform.storage.scanner.enabled=true}.
     *
     * <p>Uploads then fail if clamd is unreachable, which is the intended behaviour — content
     * that could not be scanned must not reach storage.
     */
    @Bean
    @ConditionalOnProperty(name = "platform.storage.scanner.enabled", havingValue = "true")
    MediaScanner clamAvMediaScanner(StorageProperties properties) {
        log.info("Malware scanning enabled — clamd at {}:{}",
                properties.getScanner().getHost(), properties.getScanner().getPort());
        return new ClamAvMediaScanner(properties.getScanner());
    }

    /**
     * Stands in when scanning is switched off, so the upload path calls a scanner unconditionally
     * and has no branch that can be got wrong. It logs on creation because "no malware scanning"
     * is a fact about a deployment that should be visible in its startup log, not inferred from
     * a missing property.
     */
    @Bean
    @ConditionalOnProperty(name = "platform.storage.scanner.enabled",
                           havingValue = "false", matchIfMissing = true)
    MediaScanner noOpMediaScanner() {
        log.warn("Malware scanning is DISABLED (platform.storage.scanner.enabled=false). " +
                 "Uploads are checked for format but not for content.");
        return (content, key) -> MediaScanner.ScanResult.ok();
    }

    /**
     * Lets a service start when {@code provider} names neither backend.
     *
     * <p>Keyed on the same property as the two backends, so the three conditions are exhaustive
     * and mutually exclusive and exactly one {@link StorageService} is registered regardless of
     * processing order. {@code @ConditionalOnMissingBean} would read more naturally but is only
     * order-guaranteed inside auto-configuration, and this is a plain {@code @Configuration}.
     *
     * <p>Tests that need a working stub replace this with {@code @MockitoBean}, which overrides
     * the definition rather than competing with it.
     *
     * <p>Without this, {@code provider: none} left nothing to inject and every service with an
     * upload path failed to start — which is how the end-to-end test, which sets
     * {@code STORAGE_PROVIDER=none} because it drives record CRUD rather than uploads, stopped
     * being able to boot record-service at all.
     */
    @Bean
    @Conditional(OnNoStorageBackend.class)
    StorageService unavailableStorageService() {
        log.warn("No object storage backend configured (platform.storage.provider is neither " +
                 "'gcs' nor 's3'). The service will start, but any file upload or download will " +
                 "fail.");
        return new UnavailableStorageService();
    }

    /** The confirm-time pipeline every upload path shares. */
    @Bean
    MediaIngest mediaIngest(StorageService storage, MediaScanner scanner) {
        return new MediaIngest(storage, scanner);
    }

    @Configuration
    @ConditionalOnProperty(name = "platform.storage.provider", havingValue = "gcs", matchIfMissing = true)
    static class GcsConfig {

        @Bean
        GoogleCredentials googleCredentials(StorageProperties properties) throws IOException {
            String location = properties.getGcs().getCredentialsLocation();
            if (location != null && !location.isBlank()) {
                log.warn("Loading GCS credentials from a key file ({}). Deployed environments " +
                        "should use Workload Identity instead — no key file in the pod.", location);
                try (var in = new FileInputStream(location)) {
                    return GoogleCredentials.fromStream(in);
                }
            }
            // Application Default Credentials: under Workload Identity this resolves to the
            // pod's bound service account, which has no private key — signing then goes
            // through IAM signBlob (see GcsStorageService.signingCredentials).
            return GoogleCredentials.getApplicationDefault();
        }

        @Bean
        Storage gcsStorage(GoogleCredentials credentials) {
            return StorageOptions.newBuilder().setCredentials(credentials).build().getService();
        }

        @Bean
        StorageService gcsStorageService(Storage storage,
                                          GoogleCredentials credentials,
                                          StorageProperties properties) {
            return new GcsStorageService(storage, credentials, properties);
        }
    }

    @Configuration
    @ConditionalOnProperty(name = "platform.storage.provider", havingValue = "s3")
    static class S3Config {

        @Bean
        S3Client s3Client(StorageProperties properties) {
            return S3Client.builder()
                    .region(Region.of(properties.getS3().getRegion()))
                    .build();
        }

        @Bean
        S3Presigner s3Presigner(StorageProperties properties) {
            return S3Presigner.builder()
                    .region(Region.of(properties.getS3().getRegion()))
                    .build();
        }

        @Bean
        StorageService s3StorageService(S3Client client,
                                         S3Presigner presigner,
                                         StorageProperties properties) {
            return new S3StorageService(client, presigner, properties);
        }
    }
}
