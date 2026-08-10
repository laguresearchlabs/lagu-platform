package com.lagu.platform.storage;

import com.lagu.platform.common.exception.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * The confirm-time pipeline: verify, scan, measure, promote, derive.
 *
 * <p>Order within it is load-bearing. Nothing reaches a durable key until every check has
 * passed, and anything rejected is deleted rather than left in the bucket — an orphan there is
 * still readable to whoever holds the signed URL from step 1.
 */
class MediaIngestTest {

    private final StorageService storage = mock(StorageService.class);
    private final MediaScanner scanner = mock(MediaScanner.class);
    private final MediaIngest ingest = new MediaIngest(storage, scanner);

    private static final MediaPolicy POLICY =
            MediaPolicy.of(List.of("image/jpeg", "image/png", "application/pdf"), 20);

    private static final UUID OWNER = UUID.randomUUID();

    /** An in-memory bucket, so promote/derive actually move and land somewhere observable. */
    private final Map<String, byte[]> bucket = new HashMap<>();
    private final Map<String, String> contentTypes = new HashMap<>();

    @BeforeEach
    void setUp() {
        when(scanner.scan(any(), anyString())).thenReturn(MediaScanner.ScanResult.ok());

        when(storage.stat(anyString())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            byte[] content = bucket.get(key);
            return content == null ? Optional.empty()
                    : Optional.of(new ObjectMeta(key, content.length, contentTypes.get(key), Instant.now()));
        });
        when(storage.readRange(anyString(), anyInt())).thenAnswer(inv -> {
            byte[] content = bucket.get((String) inv.getArgument(0));
            return java.util.Arrays.copyOf(content, Math.min(content.length, (int) inv.getArgument(1)));
        });
        when(storage.readAll(anyString(), anyLong())).thenAnswer(inv ->
                bucket.get((String) inv.getArgument(0)));
        doAnswer(inv -> {
            String from = inv.getArgument(0);
            String to = inv.getArgument(1);
            bucket.put(to, bucket.remove(from));
            contentTypes.put(to, contentTypes.remove(from));
            return null;
        }).when(storage).move(anyString(), anyString());
        doAnswer(inv -> {
            bucket.put(inv.getArgument(0), inv.getArgument(1));
            contentTypes.put(inv.getArgument(0), inv.getArgument(2));
            return null;
        }).when(storage).write(anyString(), any(), anyString());
        doAnswer(inv -> {
            bucket.remove((String) inv.getArgument(0));
            return null;
        }).when(storage).delete(anyString());
    }

    private static byte[] jpeg(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }

    private String upload(String fileName, String contentType, byte[] content) {
        String key = StorageKeys.buildPending("record", OWNER, fileName);
        bucket.put(key, content);
        contentTypes.put(key, contentType);
        return key;
    }

    private MediaIngest.Request.RequestBuilder request(String pendingKey) {
        return MediaIngest.Request.builder()
                .pendingKey(pendingKey)
                .policy(POLICY)
                .image(ImageConstraints.NONE)
                .derivatives(false);
    }

    // ── promotion ─────────────────────────────────────────────────────────────

    @Test
    void promotesAVerifiedUploadOutOfPending() throws IOException {
        String pending = upload("photo.jpg", "image/jpeg", jpeg(800, 600));

        MediaIngest.Result result = ingest.confirm(request(pending).build());

        assertThat(StorageKeys.isPending(result.key())).isFalse();
        assertThat(result.key()).isEqualTo(StorageKeys.promote(pending));
        assertThat(bucket).containsKey(result.key()).doesNotContainKey(pending);
        assertThat(result.contentType()).isEqualTo("image/jpeg");
        assertThat(result.width()).isEqualTo(800);
        assertThat(result.height()).isEqualTo(600);
    }

    /**
     * Presigning against a durable key would put unverified bytes straight onto a path the
     * lifecycle rule never sweeps and the record may already reference.
     */
    @Test
    void refusesAKeyThatIsNotPending() {
        assertThatThrownBy(() -> ingest.confirm(request("record/" + OWNER + "/abc_photo.jpg").build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Not a pending key");
    }

    @Test
    void rejectsAnObjectThatWasNeverUploaded() {
        String pending = StorageKeys.buildPending("record", OWNER, "photo.jpg");

        assertThatThrownBy(() -> ingest.confirm(request(pending).build()))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("No uploaded object found");
    }

    // ── rejection cleans up ───────────────────────────────────────────────────

    @Test
    void deletesTheObjectWhenItsBytesContradictItsType() {
        String pending = upload("photo.jpg", "image/jpeg", "%PDF-1.4 not a jpeg".getBytes());

        assertThatThrownBy(() -> ingest.confirm(request(pending).build()))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("does not match its declared type");
        assertThat(bucket).doesNotContainKey(pending);
    }

    @Test
    void leavesNothingBehindWhenTheSizeCapIsExceeded() throws IOException {
        String pending = upload("photo.jpg", "image/jpeg", jpeg(100, 100));
        when(storage.stat(pending)).thenReturn(Optional.of(
                new ObjectMeta(pending, 50L * 1024 * 1024, "image/jpeg", Instant.now())));

        assertThatThrownBy(() -> ingest.confirm(request(pending).build()))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("exceeds maximum size");
        verify(storage).delete(pending);
    }

    // ── scanning ──────────────────────────────────────────────────────────────

    @Test
    void rejectsAndDeletesInfectedContent() throws IOException {
        String pending = upload("photo.jpg", "image/jpeg", jpeg(100, 100));
        when(scanner.scan(any(), anyString()))
                .thenReturn(MediaScanner.ScanResult.infected("Eicar-Test-Signature"));

        assertThatThrownBy(() -> ingest.confirm(request(pending).build()))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("malware scan")
                .hasMessageContaining("Eicar-Test-Signature");
        // Infected content is the last thing that should linger in the bucket.
        assertThat(bucket).doesNotContainKey(pending);
        verify(storage, never()).move(anyString(), anyString());
    }

    /** Scanning happens before promotion, so infected bytes never touch a durable key. */
    @Test
    void scansBeforePromoting() throws IOException {
        String pending = upload("photo.jpg", "image/jpeg", jpeg(100, 100));

        ingest.confirm(request(pending).build());

        var order = inOrder(scanner, storage);
        order.verify(scanner).scan(any(), eq(pending));
        order.verify(storage).move(eq(pending), anyString());
    }

    /** The object is pulled once and that buffer serves scanner, measurement and thumbnailer. */
    @Test
    void readsTheObjectOnlyOnce() throws IOException {
        String pending = upload("photo.jpg", "image/jpeg", jpeg(900, 900));

        ingest.confirm(request(pending).derivatives(true).build());

        verify(storage, times(1)).readAll(eq(pending), anyLong());
    }

    // ── dimensions ────────────────────────────────────────────────────────────

    @Test
    void enforcesMinimumDimensions() throws IOException {
        String pending = upload("photo.jpg", "image/jpeg", jpeg(200, 150));

        assertThatThrownBy(() -> ingest.confirm(request(pending)
                .image(new ImageConstraints(800, 600, null, null)).build()))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("200×150")
                .hasMessageContaining("minimum of 800×600");
        assertThat(bucket).doesNotContainKey(pending);
    }

    @Test
    void enforcesMaximumDimensions() throws IOException {
        String pending = upload("photo.jpg", "image/jpeg", jpeg(5000, 4000));

        assertThatThrownBy(() -> ingest.confirm(request(pending)
                .image(new ImageConstraints(null, null, 4000, 4000)).build()))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("maximum");
    }

    @Test
    void acceptsAnImageInsideItsBounds() throws IOException {
        String pending = upload("photo.jpg", "image/jpeg", jpeg(1200, 900));

        MediaIngest.Result result = ingest.confirm(request(pending)
                .image(new ImageConstraints(800, 600, 4000, 4000)).build());

        assertThat(result.width()).isEqualTo(1200);
    }

    /**
     * A PDF has no pixel dimensions. A field whose rules mention them is describing its images,
     * and must not reject everything else in the process.
     */
    @Test
    void dimensionRulesDoNotRejectFormatsWithNoDimensions() {
        String pending = upload("scan.pdf", "application/pdf", "%PDF-1.4\ncontent".getBytes());

        MediaIngest.Result result = ingest.confirm(request(pending)
                .image(new ImageConstraints(800, 600, null, null)).build());

        assertThat(result.width()).isNull();
        assertThat(result.height()).isNull();
        assertThat(StorageKeys.isPending(result.key())).isFalse();
    }

    // ── derivatives ───────────────────────────────────────────────────────────

    @Test
    void buildsCardAndFullDerivativesBesideTheOriginal() throws IOException {
        String pending = upload("photo.jpg", "image/jpeg", jpeg(3000, 2000));

        MediaIngest.Result result = ingest.confirm(request(pending).derivatives(true).build());

        assertThat(result.variantKeys())
                .containsKeys(MediaIngest.CARD_VARIANT, MediaIngest.FULL_VARIANT);

        String cardKey = result.variantKeys().get(MediaIngest.CARD_VARIANT);
        assertThat(cardKey).isEqualTo(StorageKeys.variantOf(result.key(), MediaIngest.CARD_VARIANT));
        assertThat(ImageProcessor.dimensionsOf(bucket.get(cardKey)).orElseThrow().longestEdge())
                .isEqualTo(ImageProcessor.CARD_MAX_EDGE);
        assertThat(ImageProcessor.dimensionsOf(bucket.get(
                result.variantKeys().get(MediaIngest.FULL_VARIANT))).orElseThrow().longestEdge())
                .isEqualTo(ImageProcessor.FULL_MAX_EDGE);
    }

    @Test
    void skipsDerivativesWhenNotAskedFor() throws IOException {
        String pending = upload("photo.jpg", "image/jpeg", jpeg(3000, 2000));

        MediaIngest.Result result = ingest.confirm(request(pending).derivatives(false).build());

        assertThat(result.variantKeys()).isEmpty();
        verify(storage, never()).write(anyString(), any(), anyString());
    }

    /**
     * The upload is already verified and promoted by the time derivatives are built, so a
     * format with no decoder leaves the original perfectly usable. Failing here would trade a
     * cosmetic problem for a functional one.
     */
    @Test
    void anUndecodableFormatStillSucceedsWithoutDerivatives() {
        String pending = upload("scan.pdf", "application/pdf", "%PDF-1.4\ncontent".getBytes());

        MediaIngest.Result result = ingest.confirm(request(pending).derivatives(true).build());

        assertThat(result.variantKeys()).isEmpty();
        assertThat(bucket).containsKey(result.key());
    }

    /** Storage failing on a derivative write must not undo a confirmed upload either. */
    @Test
    void aFailedDerivativeWriteDoesNotFailTheUpload() throws IOException {
        String pending = upload("photo.jpg", "image/jpeg", jpeg(900, 900));
        doThrow(new StorageException("bucket unavailable"))
                .when(storage).write(anyString(), any(), anyString());

        MediaIngest.Result result = ingest.confirm(request(pending).derivatives(true).build());

        assertThat(result.variantKeys()).isEmpty();
        assertThat(result.key()).isNotNull();
    }
}
