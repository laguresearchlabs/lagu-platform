package com.lagu.platform.storage;

import com.lagu.platform.common.exception.ValidationException;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Everything that happens to an upload between the client's PUT finishing and the key being
 * persisted: verify, scan, measure, promote, derive.
 *
 * <p>It exists because that sequence had started to appear in three places — record file fields,
 * record galleries, and documents — and the order within it is load-bearing in ways that are easy
 * to get subtly wrong when copied. Nothing is written to the durable key until every check has
 * passed, and anything rejected is removed from the bucket rather than left behind, because an
 * orphan there is still readable to whoever holds the signed URL from step 1.
 *
 * <p>The object is read into memory exactly once and that buffer serves the scanner, the
 * dimension check and the thumbnailer. Reading it three times would triple both the egress and
 * the confirm latency for no benefit.
 */
@RequiredArgsConstructor
@Slf4j
public class MediaIngest {

    /** Suffixes for the two derivative sizes. Public so callers can reason about the keys. */
    public static final String CARD_VARIANT = "card";
    public static final String FULL_VARIANT = "full";

    private final StorageService storage;
    private final MediaScanner scanner;

    /**
     * @param pendingKey  the key the client uploaded to — must be under {@code pending/}
     * @param policy      formats and size cap for this slot
     * @param image       pixel bounds, or {@link ImageConstraints#NONE}
     * @param derivatives whether to build card/full thumbnails; false for documents, which are
     *                    reviewed at full size and never rendered as tiles
     */
    @Builder
    public record Request(String pendingKey, MediaPolicy policy,
                          ImageConstraints image, boolean derivatives) {
    }

    /**
     * @param key         the durable key — the pending one no longer exists
     * @param variantKeys derivative keys by variant name, empty when none could be built
     */
    @Builder
    public record Result(String key, String contentType, long sizeBytes,
                         Integer width, Integer height, Map<String, String> variantKeys) {
    }

    /**
     * Verifies a completed upload and promotes it to its durable key.
     *
     * @throws ValidationException when the object fails any check; the object is deleted first
     */
    public Result confirm(Request request) {
        String pendingKey = request.pendingKey();
        if (!StorageKeys.isPending(pendingKey)) {
            // Not a client-facing case: it means a caller presigned against a non-pending key,
            // which would put an unverified object straight onto a durable path.
            throw new IllegalArgumentException("Not a pending key: " + pendingKey);
        }

        ObjectMeta meta = storage.stat(pendingKey).orElseThrow(() ->
                new ValidationException("No uploaded object found for key " + pendingKey));

        // What the object will be *served* as, which is what a browser acts on. Trustworthy
        // because presignUpload bound Content-Type into the signature.
        String contentType = meta.contentType() == null ? null : meta.contentType().toLowerCase();

        byte[] content;
        ImageProcessor.Dimensions dimensions;
        try {
            // The filename comes off the key rather than from the request: the key was built
            // from an already-sanitized name at step 1, so it is the one name in this flow that
            // is not a fresh client claim.
            request.policy().checkDeclared(
                    StorageKeys.fileNameOf(pendingKey), contentType, meta.sizeBytes());
            request.policy().checkStored(contentType, meta.sizeBytes(),
                    storage.readRange(pendingKey, ContentTypeSniffer.HEADER_BYTES));

            // Only now is the whole object worth pulling: it is the right format and within the
            // size cap, so the read is bounded by a limit that has already been enforced.
            content = storage.readAll(pendingKey, request.policy().maxSizeBytes());

            scan(content, pendingKey);
            dimensions = measure(content, request.image());
        } catch (ValidationException e) {
            storage.delete(pendingKey);
            throw e;
        }

        String key = StorageKeys.promote(pendingKey);
        storage.move(pendingKey, key);

        Map<String, String> variantKeys = request.derivatives()
                ? buildDerivatives(key, content)
                : Map.of();

        return Result.builder()
                .key(key)
                .contentType(contentType)
                .sizeBytes(meta.sizeBytes())
                .width(dimensions == null ? null : dimensions.width())
                .height(dimensions == null ? null : dimensions.height())
                .variantKeys(variantKeys)
                .build();
    }

    private void scan(byte[] content, String key) {
        MediaScanner.ScanResult result = scanner.scan(content, key);
        if (!result.clean()) {
            // A ValidationException rather than a bare error, so the caller's reject path deletes
            // the object — infected content is the last thing that should linger in the bucket.
            throw new ValidationException(
                    "File failed a malware scan (" + result.signature() + ")");
        }
    }

    /**
     * @return the image's dimensions, or null when the format has no decoder
     */
    private ImageProcessor.Dimensions measure(byte[] content, ImageConstraints constraints) {
        Optional<ImageProcessor.Dimensions> dimensions = ImageProcessor.dimensionsOf(content);
        if (dimensions.isEmpty()) {
            if (constraints != null && !constraints.isEmpty()) {
                // A PDF has no pixel dimensions and a HEIC has no decoder here. Neither is an
                // error, but silently passing a field that asked for a minimum size would make
                // the rule look enforced when it is not.
                log.debug("Cannot measure this format; dimension rules do not apply");
            }
            return null;
        }
        if (constraints != null) {
            constraints.check(dimensions.get());
        }
        return dimensions.get();
    }

    /**
     * Card and full-size copies, written beside the original.
     *
     * <p>Best-effort by design: the upload has already been verified and promoted by this point,
     * so a format with no decoder — or an image that simply will not scale — leaves the original
     * perfectly usable. Failing the whole upload because a thumbnail could not be produced would
     * trade a cosmetic problem for a functional one.
     */
    private Map<String, String> buildDerivatives(String key, byte[] content) {
        Map<String, String> variants = new LinkedHashMap<>();
        addVariant(variants, key, content, CARD_VARIANT, ImageProcessor.CARD_MAX_EDGE);
        addVariant(variants, key, content, FULL_VARIANT, ImageProcessor.FULL_MAX_EDGE);
        return variants;
    }

    private void addVariant(Map<String, String> variants, String key, byte[] content,
                            String name, int maxEdge) {
        try {
            Optional<byte[]> scaled = ImageProcessor.scaleToFit(content, maxEdge);
            if (scaled.isEmpty()) return;

            String variantKey = StorageKeys.variantOf(key, name);
            storage.write(variantKey, scaled.get(), ImageProcessor.DERIVATIVE_CONTENT_TYPE);
            variants.put(name, variantKey);
        } catch (RuntimeException e) {
            log.warn("Could not write the {} derivative for {}: {}", name, key, e.toString());
        }
    }
}
