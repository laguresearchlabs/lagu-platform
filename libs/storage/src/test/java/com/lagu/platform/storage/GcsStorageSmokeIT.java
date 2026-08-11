package com.lagu.platform.storage;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@link GcsStorageService} against a real bucket.
 *
 * <p>Every other test in this library mocks {@code StorageService}, which means the parts that
 * only exist to talk to GCS — V4 signing, server-side copy, ranged reads, the bucket's own
 * rejection of a mismatched Content-Type — have never actually run. This closes that gap.
 *
 * <p><b>Opt-in.</b> Skipped unless {@code GCS_SMOKE_BUCKET} is set, so a normal build and CI are
 * unaffected. Point it at a scratch bucket, never a production one: it writes and deletes objects
 * under a {@code smoke-test/} prefix.
 *
 * <pre>
 * GCS_SMOKE_BUCKET=my-bucket \
 * GCS_SMOKE_CREDENTIALS=/path/to/sa-key.json \
 *   ./gradlew :libs:storage:test --tests '*GcsStorageSmokeIT*'
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "GCS_SMOKE_BUCKET", matches = ".+")
class GcsStorageSmokeIT {

    /** Prefix so anything this leaves behind is obvious and safe to purge. */
    private static final String DOMAIN = "smoke-test";

    private static StorageService storage;
    private static Storage gcsClient;
    private static StorageProperties properties;
    private static final List<String> written = new ArrayList<>();

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20)).build();

    @BeforeAll
    static void connect() throws IOException {
        properties = new StorageProperties();
        properties.setDomain(DOMAIN);
        properties.getGcs().setBucket(System.getenv("GCS_SMOKE_BUCKET"));

        String keyPath = System.getenv("GCS_SMOKE_CREDENTIALS");
        GoogleCredentials credentials;
        if (keyPath != null && !keyPath.isBlank()) {
            try (var in = new FileInputStream(keyPath)) {
                credentials = GoogleCredentials.fromStream(in);
            }
        } else {
            credentials = GoogleCredentials.getApplicationDefault();
        }

        gcsClient = StorageOptions.newBuilder().setCredentials(credentials).build().getService();
        storage = new GcsStorageService(gcsClient, credentials, properties);
    }

    @AfterAll
    static void cleanUp() {
        // Best-effort: a failed assertion must not leave objects behind, and a failure to clean
        // up must not mask the assertion that actually failed.
        for (String key : written) {
            try {
                storage.delete(key);
            } catch (RuntimeException ignored) {
                // Already gone, or the test that created it never got that far.
            }
        }

        // Then prove it. This runs against a bucket someone else owns, so "we deleted what we
        // tracked" is not the same as "nothing is left" — a key created but never tracked would
        // slip through silently, and the next person to look would find litter with no idea
        // which run produced it.
        List<String> leftovers = new ArrayList<>();
        gcsClient.list(properties.getGcs().getBucket(),
                        Storage.BlobListOption.prefix(DOMAIN + "/"))
                .iterateAll()
                .forEach(blob -> leftovers.add(blob.getName()));

        assertThat(leftovers)
                .as("objects left under %s/ in bucket %s", DOMAIN, properties.getGcs().getBucket())
                .isEmpty();
    }

    private static String track(String key) {
        written.add(key);
        return key;
    }

    private static byte[] jpeg(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }

    /** PUTs bytes to a presigned URL exactly as a browser would. */
    private static int put(String url, byte[] body, String contentType) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", contentType)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
                .timeout(Duration.ofSeconds(60))
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    // ── the whole upload round trip ───────────────────────────────────────────

    /**
     * The complete confirm-time path, end to end: presign, PUT, stat, ranged read, full read,
     * promote out of pending, write a derivative, sign a download and fetch it.
     *
     * <p>Deliberately one test rather than several. Each step depends on the object the previous
     * one produced, and splitting them would mean either re-uploading per test or sharing mutable
     * state between them.
     */
    @Test
    void presignUploadConfirmPromoteAndDownload() throws Exception {
        UUID ownerId = UUID.randomUUID();
        byte[] content = jpeg(1200, 800);

        // 1. Presign, and PUT straight to the bucket.
        String pendingKey = track(StorageKeys.buildPending(DOMAIN, ownerId, "smoke.jpg"));
        PresignedUpload upload = storage.presignUpload(pendingKey, "image/jpeg", Duration.ofMinutes(10));
        assertThat(upload.url()).startsWith("https://");

        assertThat(put(upload.url(), content, "image/jpeg"))
                .as("PUT to presigned URL")
                .isBetween(200, 299);

        // 2. stat — the bucket's own measurement, which is what confirm trusts over the client's.
        Optional<ObjectMeta> meta = storage.stat(pendingKey);
        assertThat(meta).isPresent();
        assertThat(meta.get().sizeBytes()).isEqualTo(content.length);
        assertThat(meta.get().contentType()).isEqualTo("image/jpeg");

        // 3. readRange — the magic-byte sniff.
        byte[] header = storage.readRange(pendingKey, ContentTypeSniffer.HEADER_BYTES);
        assertThat(ContentTypeSniffer.matches(header, "image/jpeg")).isTrue();

        // 4. readAll — what the scanner and thumbnailer consume.
        byte[] full = storage.readAll(pendingKey, 25L * 1024 * 1024);
        assertThat(full).hasSize(content.length);
        assertThat(ImageProcessor.dimensionsOf(full).orElseThrow().width()).isEqualTo(1200);

        // 5. move — server-side promotion out of pending/.
        String durableKey = track(StorageKeys.promote(pendingKey));
        storage.move(pendingKey, durableKey);
        assertThat(storage.stat(durableKey)).isPresent();
        assertThat(storage.stat(pendingKey)).as("pending copy removed").isEmpty();

        // 6. write — a derivative, produced by this process rather than uploaded.
        byte[] thumb = ImageProcessor.scaleToFit(full, ImageProcessor.CARD_MAX_EDGE).orElseThrow();
        String cardKey = track(StorageKeys.variantOf(durableKey, MediaIngest.CARD_VARIANT));
        storage.write(cardKey, thumb, ImageProcessor.DERIVATIVE_CONTENT_TYPE);
        assertThat(storage.stat(cardKey)).isPresent();

        // 7. presignDownload — and actually fetch it, since a URL that does not resolve is the
        //    failure this whole test exists to catch.
        String downloadUrl = storage.presignDownload(cardKey, Duration.ofMinutes(5));
        HttpResponse<byte[]> fetched = HTTP.send(
                HttpRequest.newBuilder(URI.create(downloadUrl)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(fetched.statusCode()).isEqualTo(200);
        assertThat(fetched.body()).hasSize(thumb.length);
    }

    /**
     * The signature binds Content-Type, so the bucket itself rejects a PUT that declares something
     * else. That binding is the reason confirm can treat the stored content type as trustworthy —
     * if it did not hold, every downstream check would be reasoning about a client's claim.
     */
    @Test
    void bucketRejectsAPutWhoseContentTypeDoesNotMatchTheSignature() throws Exception {
        UUID ownerId = UUID.randomUUID();
        String key = track(StorageKeys.buildPending(DOMAIN, ownerId, "mismatch.jpg"));

        PresignedUpload upload = storage.presignUpload(key, "image/jpeg", Duration.ofMinutes(10));

        assertThat(put(upload.url(), jpeg(64, 64), "application/pdf"))
                .as("PUT declaring a different Content-Type than was signed")
                .isBetween(400, 499);
        assertThat(storage.stat(key)).as("nothing stored").isEmpty();
    }

    @Test
    void deleteRemovesTheObjectAndIsSilentWhenAlreadyGone() throws Exception {
        UUID ownerId = UUID.randomUUID();
        String key = StorageKeys.buildPending(DOMAIN, ownerId, "delete-me.jpg");

        PresignedUpload upload = storage.presignUpload(key, "image/jpeg", Duration.ofMinutes(10));
        put(upload.url(), jpeg(32, 32), "image/jpeg");
        assertThat(storage.stat(key)).isPresent();

        storage.delete(key);
        assertThat(storage.stat(key)).isEmpty();

        // Confirm's reject path deletes unconditionally, so a second delete must not throw.
        storage.delete(key);
    }

    /** readAll refuses an object larger than the caller's policy rather than buffering it. */
    @Test
    void readAllRefusesAnObjectAboveTheLimit() throws Exception {
        UUID ownerId = UUID.randomUUID();
        String key = track(StorageKeys.buildPending(DOMAIN, ownerId, "big.jpg"));

        byte[] content = jpeg(800, 600);
        PresignedUpload upload = storage.presignUpload(key, "image/jpeg", Duration.ofMinutes(10));
        put(upload.url(), content, "image/jpeg");

        org.junit.jupiter.api.Assertions.assertThrows(StorageException.class,
                () -> storage.readAll(key, content.length - 1));
    }
}
