package com.lagu.platform.storage;

import java.time.Duration;
import java.util.Optional;

/**
 * Object storage, addressed by key. Blob bytes never transit the JVM: callers mint a presigned
 * URL, the client transfers directly to/from the bucket, and only the key is persisted.
 *
 * <p>Replaces the former standalone image-service, whose upload endpoint buffered whole
 * multipart bodies into heap ({@code file.getBytes()}) and returned a 10-minute signed URL that
 * callers then persisted as if it were durable. <b>Store the key; sign on read.</b>
 */
public interface StorageService {

    /**
     * Mints a URL the client can PUT to directly.
     *
     * <p>{@code contentType} is bound into the signature, so the client must send a matching
     * {@code Content-Type} header or the bucket rejects the upload. That binding is a
     * consistency check, not a security control — the client still chooses the bytes, so
     * anything security-relevant must be re-verified after the fact via
     * {@link #stat} and {@link #readRange}.
     */
    PresignedUpload presignUpload(String key, String contentType, Duration ttl);

    /** Mints a short-lived URL for reading an object. Generate per request; never persist. */
    String presignDownload(String key, Duration ttl);

    /**
     * Server-side metadata for an object, or empty when it does not exist.
     *
     * <p>Note that size and content type here reflect what the client sent on the PUT, so they
     * confirm an upload actually landed but do not attest to what the bytes are.
     */
    Optional<ObjectMeta> stat(String key);

    /**
     * Moves an object, server-side. The bytes are copied within the bucket and never reach this
     * process.
     *
     * <p>Exists for the pending→final promotion at confirm time. Uploads land under a
     * {@code pending/} prefix so a bucket lifecycle rule can sweep the ones nobody ever
     * confirmed; an object only reaches its durable key once it has been verified, which is what
     * makes "old and still pending" a safe thing to delete automatically.
     *
     * <p>Not atomic — a copy followed by a delete. A crash between the two leaves the object at
     * both keys, which the lifecycle rule then cleans up on its own schedule.
     */
    void move(String fromKey, String toKey);

    /**
     * Reads an object in full, up to {@code maxBytes}.
     *
     * <p>The one place bytes deliberately transit this process. Scanning an upload for malware
     * and decoding it to build thumbnails both need the whole object, and neither can be done
     * from a ranged read. {@code maxBytes} is the guard that keeps that from being an
     * out-of-memory vector: callers pass their policy's size cap, and an object larger than it
     * is refused rather than buffered.
     *
     * @throws StorageException when the object is larger than {@code maxBytes}
     */
    byte[] readAll(String key, long maxBytes);

    /**
     * Writes an object directly, for content this service produced rather than received —
     * derivatives, specifically. Uploads still go client-to-bucket via a presigned URL.
     */
    void write(String key, byte[] content, String contentType);

    /**
     * Reads the first {@code length} bytes of an object.
     *
     * <p>Exists so a confirm-upload step can sniff magic bytes. With direct-to-bucket uploads
     * the service never sees the payload, which would otherwise silently drop the content
     * check that {@code DocumentService} previously ran on the multipart body — a renamed
     * executable declared as {@code application/pdf} would pass content-type and extension
     * validation with nothing left to catch it. A ranged read restores that check for the cost
     * of a few hundred bytes.
     *
     * <p>Returns fewer bytes than requested if the object is shorter.
     */
    byte[] readRange(String key, int length);

    /** Deletes an object. Succeeds silently when the key is already absent. */
    void delete(String key);
}
