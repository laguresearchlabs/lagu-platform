package com.lagu.platform.storage;

import com.lagu.platform.common.exception.ValidationException;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * What a given upload slot will accept: which content types, and how large.
 *
 * <p>Every service that takes uploads was carrying its own copy of this — {@code DocumentService}
 * had {@code ALLOWED_CONTENT_TYPES}/{@code ALLOWED_EXTENSIONS}/{@code MAX_FILE_SIZE_BYTES} as
 * private constants, {@code RecordFileController} had two more sets and no size limit at all, so
 * a record file field accepted objects of unbounded size. Worse, the limits admins could already
 * edit in schema-registry ({@code DocumentRequirement.allowedMimeTypes}, {@code maxSizeMb}) were
 * written to the database and then ignored by the service enforcing them.
 *
 * <p>This type is the enforcement point both services now share, and {@link #overriddenBy} is
 * how admin configuration reaches it: a service declares a built-in default, and whatever the
 * admin configured for that document type or field narrows or replaces it. Configuration that is
 * absent leaves the default standing, so an unconfigured slot is still a guarded one.
 *
 * <p>The two checks are deliberately separate and both are needed. {@link #checkDeclared} runs
 * before a presigned URL is minted and sees only what the client claims — cheap, and enough to
 * refuse an obviously bad upload before bytes move. {@link #checkStored} runs at confirm time
 * against the bucket's own measurement and the object's real leading bytes. Only the second one
 * establishes anything.
 */
public record MediaPolicy(Set<String> allowedContentTypes, long maxSizeBytes) {

    public MediaPolicy {
        if (allowedContentTypes == null || allowedContentTypes.isEmpty()) {
            throw new IllegalArgumentException("allowedContentTypes must not be empty");
        }
        if (maxSizeBytes <= 0) {
            throw new IllegalArgumentException("maxSizeBytes must be positive");
        }
        allowedContentTypes = normalize(allowedContentTypes);
    }

    public static MediaPolicy of(Collection<String> contentTypes, long maxSizeMb) {
        return new MediaPolicy(new LinkedHashSet<>(contentTypes), maxSizeMb * 1024 * 1024);
    }

    /**
     * This policy with admin configuration applied over it. Either argument may be null or empty,
     * in which case that half of the policy is left as it is.
     *
     * <p>{@code contentTypes} <em>replaces</em> rather than intersects: an admin adding a format
     * to a document type expects it to be accepted, and intersecting with a compiled-in default
     * would silently drop it — the exact failure this class exists to end. The real ceiling is
     * {@link ContentTypeSniffer#supportedTypes()}, which is enforced by
     * {@link #unverifiableTypes()} rather than by quietly discarding the admin's intent.
     */
    public MediaPolicy overriddenBy(Collection<String> contentTypes, Integer maxSizeMb) {
        Set<String> types = (contentTypes == null || contentTypes.isEmpty())
                ? allowedContentTypes
                : new LinkedHashSet<>(contentTypes);
        long size = (maxSizeMb == null || maxSizeMb <= 0)
                ? maxSizeBytes
                : maxSizeMb * 1024L * 1024L;
        return new MediaPolicy(types, size);
    }

    /**
     * Configured types the platform cannot actually verify, so it will reject every upload of
     * them at confirm time.
     *
     * <p>Non-empty means someone configured a format {@link ContentTypeSniffer} has no signature
     * for. That is not a policy this class can enforce halfway: the sniffer fails closed, so the
     * slot is broken rather than permissive. Services surface this when they load configuration
     * so it reads as a misconfiguration at startup instead of as inexplicable upload failures.
     */
    public Set<String> unverifiableTypes() {
        Set<String> unverifiable = new TreeSet<>();
        for (String type : allowedContentTypes) {
            if (!ContentTypeSniffer.isVerifiable(type)) unverifiable.add(type);
        }
        return unverifiable;
    }

    /** Extensions implied by the allowed content types — never maintained separately from them. */
    public Set<String> allowedExtensions() {
        Set<String> extensions = new TreeSet<>();
        allowedContentTypes.forEach(type -> extensions.addAll(ContentTypeSniffer.extensionsFor(type)));
        return extensions;
    }

    /**
     * Gate for step 1, on the client's declarations alone.
     *
     * <p>Proves nothing about the eventual object — the client can declare one thing and upload
     * another, which is what {@link #checkStored} is for. Its value is refusing an upload that
     * cannot possibly succeed before a URL is minted and megabytes are transferred.
     */
    public void checkDeclared(String fileName, String contentType, long sizeBytes) {
        if (sizeBytes <= 0) {
            throw new ValidationException("File must not be empty");
        }
        if (sizeBytes > maxSizeBytes) {
            throw new ValidationException("File exceeds maximum size of " + maxSizeMbLabel());
        }
        requireAllowedType(contentType);

        // Extension is checked only here. It is pure convention with no bearing on the bytes, so
        // once the object exists the signature check supersedes it entirely.
        Set<String> allowedExtensions = allowedExtensions();
        String extension = StorageKeys.extensionOf(fileName);
        if (extension == null || !allowedExtensions.contains(extension)) {
            throw new ValidationException(
                    "Unsupported file extension: " + extension + ". Allowed: " + allowedExtensions);
        }
    }

    /**
     * Gate for step 3, on the stored object.
     *
     * @param contentType the type the object was stored under — bound into the upload signature,
     *                    so the bucket already rejected a PUT declaring anything else
     * @param sizeBytes   the bucket's own measurement, unlike the value declared at step 1
     * @param header      the object's first {@link ContentTypeSniffer#HEADER_BYTES} bytes
     */
    public void checkStored(String contentType, long sizeBytes, byte[] header) {
        if (sizeBytes <= 0) {
            throw new ValidationException("File must not be empty");
        }
        if (sizeBytes > maxSizeBytes) {
            throw new ValidationException("File exceeds maximum size of " + maxSizeMbLabel());
        }
        requireAllowedType(contentType);

        if (!ContentTypeSniffer.matches(header, contentType)) {
            throw new ValidationException(
                    "File content does not match its declared type (" + contentType + ")");
        }
    }

    private void requireAllowedType(String contentType) {
        if (contentType == null || !allowedContentTypes.contains(contentType.trim().toLowerCase())) {
            throw new ValidationException(
                    "Unsupported file type: " + contentType + ". Allowed: " + allowedContentTypes);
        }
    }

    private String maxSizeMbLabel() {
        long mb = maxSizeBytes / (1024 * 1024);
        return mb > 0 ? mb + "MB" : maxSizeBytes + " bytes";
    }

    private static Set<String> normalize(Collection<String> contentTypes) {
        Set<String> normalized = new LinkedHashSet<>();
        for (String type : contentTypes) {
            if (type != null && !type.isBlank()) normalized.add(type.trim().toLowerCase());
        }
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("allowedContentTypes must not be empty");
        }
        // Insertion order is kept rather than using Set.copyOf: this set is rendered verbatim
        // into the "Allowed: …" message a vendor sees, and an arbitrary order there reads as a
        // different list every time the JVM restarts.
        return java.util.Collections.unmodifiableSet(normalized);
    }
}
