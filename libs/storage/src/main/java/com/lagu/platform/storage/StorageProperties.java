package com.lagu.platform.storage;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "platform.storage")
public class StorageProperties {

    /** Backend to use: {@code gcs} or {@code s3}. */
    private String provider = "gcs";

    /**
     * Key prefix this service owns, e.g. {@code record} or {@code document}. Every key it mints
     * lands under this, and its IAM binding should be scoped to the same prefix — that pairing
     * is what keeps one service from writing over another's objects now that bucket access is
     * no longer funnelled through a single image-service pod.
     */
    private String domain;

    /** How long presigned URLs stay valid. Short by design: they are minted per request. */
    private java.time.Duration uploadUrlTtl = java.time.Duration.ofMinutes(15);
    private java.time.Duration downloadUrlTtl = java.time.Duration.ofMinutes(10);

    private final Gcs gcs = new Gcs();
    private final S3 s3 = new S3();
    private final Scanner scanner = new Scanner();

    @Data
    public static class Scanner {

        /**
         * Whether confirmed uploads are scanned for malware before being persisted.
         *
         * <p>Defaults to off so local development and tests do not need a daemon running, and is
         * enabled per environment. When it is on, an unreachable clamd fails uploads — that is
         * the point, and this flag is the supported way to switch it off rather than letting a
         * timeout do it silently.
         */
        private boolean enabled = false;

        private String host = "clamav";
        private int port = 3310;

        /**
         * Applies to both connect and read. Generous, because clamd scans in-line and a large
         * document takes real time — but finite, so a stalled daemon fails the request instead
         * of holding a thread.
         */
        private java.time.Duration timeout = java.time.Duration.ofSeconds(30);
    }

    @Data
    public static class Gcs {
        private String bucket;

        /**
         * Service account to sign as, for keyless setups only. With no private key available
         * locally, signing goes through IAM {@code signBlob} while impersonating this account,
         * which then needs {@code roles/iam.serviceAccountTokenCreator} on itself.
         *
         * <p>Leave unset when {@link #credentialsLocation} is set — a key file signs locally.
         */
        private String serviceAccountEmail;

        /**
         * Path to a service-account JSON key.
         *
         * <p>This is the deployed configuration today: production runs on on-prem k3s, where
         * there is no GCP Workload Identity, so a mounted key file <em>is</em> the service's
         * identity. Each service mounts its own key, IAM-conditioned to its own object prefix
         * — that scoping is what keeps one service out of another's files, and it is why the
         * key is per-service rather than shared.
         */
        private String credentialsLocation;
    }

    @Data
    public static class S3 {
        private String bucket;
        private String region;
    }
}
