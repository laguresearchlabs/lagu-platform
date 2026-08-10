package com.lagu.platform.storage;

import java.util.UUID;

/**
 * The one place object keys are built, so they stay parseable and prefix-scoped.
 *
 * <p>Layout is {@code {domain}/{ownerId}/{uuid}_{sanitizedFilename}} — e.g.
 * {@code record/3f1c…/9ab2…_site-plan.pdf}. The leading {@code domain/} segment is what the
 * per-service IAM bindings scope on, so a service can only write under its own prefix; keeping
 * generation here is what makes that guarantee hold.
 *
 * <p>The old image-service used a flat {@code {uuid}_{filename}} key with no prefix at all,
 * which is why every consumer needed credentials to the whole bucket.
 */
public final class StorageKeys {

    private StorageKeys() {
    }

    /**
     * Segment that uploads land under until they are confirmed.
     *
     * <p>An upload URL can be minted, used, and then never confirmed — the vendor closes the tab,
     * the request fails, the app crashes. That object is unreferenced but permanent, and no
     * lifecycle rule can safely sweep it while it shares a prefix with objects that are supposed
     * to live forever. Under this segment "old" and "abandoned" mean the same thing, which is
     * what lets the bucket clean up after itself. See {@code tools/storage/lifecycle.sh}.
     *
     * <p><b>It sits directly after the domain</b> — {@code record/pending/…}, not
     * {@code record/{owner}/pending/…} — and that position is the whole point. Lifecycle
     * conditions on both GCS and S3 match a prefix of the full object name, so a segment in the
     * middle of the key cannot be targeted by one; {@code record/pending/} can. Putting it after
     * the domain rather than before keeps the domain prefix intact, so the per-service IAM
     * binding still covers pending and durable objects alike.
     */
    public static final String PENDING_SEGMENT = "pending";

    /**
     * Builds the key an upload is presigned against — always under {@link #PENDING_SEGMENT}.
     * {@link #promote} moves it to its durable key once the bytes have been verified.
     *
     * <p>The UUID makes it collision-free, so callers never need to check.
     */
    public static String buildPending(String domain, UUID ownerId, String originalFilename) {
        requireDomainAndOwner(domain, ownerId);
        return domain + "/" + PENDING_SEGMENT + "/" + ownerId + "/"
                + UUID.randomUUID() + "_" + sanitizeFileName(originalFilename);
    }

    /** The prefix a lifecycle rule sweeps for one service. */
    public static String pendingPrefix(String domain) {
        return domain + "/" + PENDING_SEGMENT + "/";
    }

    /**
     * The durable key for a pending one: the same key with {@code pending/} taken out.
     *
     * <p>Deriving it rather than generating a fresh one keeps the UUID and filename stable across
     * the move, so a key in a log line still identifies the same object on both sides of confirm.
     */
    public static String promote(String pendingKey) {
        if (!isPending(pendingKey)) {
            throw new IllegalArgumentException("Not a pending key: " + pendingKey);
        }
        return pendingKey.replaceFirst("/" + PENDING_SEGMENT + "/", "/");
    }

    public static boolean isPending(String key) {
        return key != null && key.contains("/" + PENDING_SEGMENT + "/");
    }

    /**
     * Whether {@code key} belongs to {@code ownerId} under {@code domain}, in either the pending
     * or the durable layout.
     *
     * <p>Ownership is the check that stops a caller with rights on one record confirming — or
     * being handed a signed URL for — an object belonging to another. It lives here because the
     * key has two shapes either side of confirm, and every call site open-coding that against
     * {@code startsWith} is how one of them ends up checking only one shape.
     */
    public static boolean isOwnedBy(String key, String domain, UUID ownerId) {
        if (key == null || domain == null || ownerId == null) return false;
        return key.startsWith(domain + "/" + ownerId + "/")
                || key.startsWith(pendingPrefix(domain) + ownerId + "/");
    }

    /** Builds a key alongside {@code key} distinguished by {@code suffix} — for derivatives, so
     *  a thumbnail sits next to its original and is deleted by the same prefix rules. */
    public static String variantOf(String key, String suffix) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key is required");
        }
        int dot = key.lastIndexOf('.');
        int slash = key.lastIndexOf('/');
        return dot > slash
                ? key.substring(0, dot) + "__" + suffix + key.substring(dot)
                : key + "__" + suffix;
    }

    private static void requireDomainAndOwner(String domain, UUID ownerId) {
        if (domain == null || domain.isBlank()) {
            throw new IllegalArgumentException("domain is required");
        }
        if (ownerId == null) {
            throw new IllegalArgumentException("ownerId is required");
        }
    }

    /** True when {@code key} sits under {@code domain/}. Guards confirm-upload against a client
     *  returning a key the service never issued. */
    public static boolean isUnderDomain(String key, String domain) {
        return key != null && domain != null && key.startsWith(domain + "/");
    }

    /**
     * Strips path separators and anything outside {@code [A-Za-z0-9._-]}, keeping the name
     * readable. Lifted from {@code DocumentService.sanitizeFileName} so both services and the
     * key builder share one definition.
     */
    public static String sanitizeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "unnamed";
        }
        String base = fileName.replace("\\", "/");
        base = base.substring(base.lastIndexOf('/') + 1);
        base = base.replaceAll("[^A-Za-z0-9._-]", "_");
        if (base.isBlank()) {
            return "unnamed";
        }
        return base.length() > 255 ? base.substring(base.length() - 255) : base;
    }

    /**
     * The sanitized filename a key was built around — everything after the {@code uuid_} prefix.
     *
     * <p>Lets confirm-time checks work from the key alone instead of asking the client to resend
     * a name. The key was built by {@link #buildPending} from an already-sanitized name, so this
     * is the one filename in the flow that is not a fresh client claim.
     */
    public static String fileNameOf(String key) {
        if (key == null || key.isBlank()) return "unnamed";
        String last = key.substring(key.lastIndexOf('/') + 1);
        int underscore = last.indexOf('_');
        return underscore >= 0 && underscore < last.length() - 1
                ? last.substring(underscore + 1)
                : last;
    }

    /** Lowercase extension without the dot, or null when there isn't one. */
    public static String extensionOf(String fileName) {
        if (fileName == null) return null;
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) return null;
        return fileName.substring(dot + 1).toLowerCase();
    }
}
