package com.lagu.platform.storage;

import com.lagu.platform.common.exception.ValidationException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The point of this class is that admin-configured media limits are actually enforced.
 * schema-registry has stored {@code allowed_mime_types} and {@code max_size_mb} per document
 * type since it was built, and the services enforcing them used compiled-in constants instead —
 * so the admin screen saved happily and changed nothing.
 */
class MediaPolicyTest {

    private static final MediaPolicy DEFAULT = MediaPolicy.of(
            List.of("image/jpeg", "image/png", "application/pdf"), 20);

    private static byte[] pdfHeader() {
        byte[] header = new byte[ContentTypeSniffer.HEADER_BYTES];
        System.arraycopy(new byte[]{'%', 'P', 'D', 'F'}, 0, header, 0, 4);
        return header;
    }

    private static byte[] jpegHeader() {
        byte[] header = new byte[ContentTypeSniffer.HEADER_BYTES];
        System.arraycopy(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}, 0, header, 0, 3);
        return header;
    }

    @Test
    void acceptsAnUploadInsideThePolicy() {
        assertDoesNotThrow(() -> DEFAULT.checkDeclared("scan.pdf", "application/pdf", 1024));
        assertDoesNotThrow(() -> DEFAULT.checkStored("application/pdf", 1024, pdfHeader()));
    }

    @Test
    void adminConfigurationNarrowsTheAllowedTypes() {
        MediaPolicy pdfOnly = DEFAULT.overriddenBy(List.of("application/pdf"), null);

        assertDoesNotThrow(() -> pdfOnly.checkDeclared("scan.pdf", "application/pdf", 1024));
        assertThrows(ValidationException.class,
                () -> pdfOnly.checkDeclared("photo.jpg", "image/jpeg", 1024));
    }

    /**
     * Configuration replaces the default list rather than intersecting with it. An admin adding
     * a format expects it accepted; intersecting would silently discard the change, which is the
     * exact failure mode this class exists to end.
     */
    @Test
    void adminConfigurationCanAlsoWidenBeyondTheDefault() {
        MediaPolicy withGif = DEFAULT.overriddenBy(
                List.of("application/pdf", "image/gif"), null);

        assertDoesNotThrow(() -> withGif.checkDeclared("logo.gif", "image/gif", 1024));
        // Replaced, not merged — a type left out of the configured list is no longer allowed.
        assertThrows(ValidationException.class,
                () -> withGif.checkDeclared("photo.jpg", "image/jpeg", 1024));
    }

    @Test
    void adminConfigurationSetsTheSizeCap() {
        MediaPolicy small = DEFAULT.overriddenBy(null, 1);

        assertDoesNotThrow(() -> small.checkDeclared("scan.pdf", "application/pdf", 512 * 1024));
        ValidationException tooBig = assertThrows(ValidationException.class,
                () -> small.checkDeclared("scan.pdf", "application/pdf", 2 * 1024 * 1024));
        assertTrue(tooBig.getMessage().contains("1MB"));
    }

    /** Absent configuration must leave the default standing — an unconfigured slot is still
     *  a guarded one, never an unlimited one. */
    @Test
    void absentConfigurationLeavesTheDefaultIntact() {
        assertEquals(DEFAULT, DEFAULT.overriddenBy(null, null));
        assertEquals(DEFAULT, DEFAULT.overriddenBy(List.of(), 0));
    }

    @Test
    void rejectsAnObjectWhoseBytesContradictItsType() {
        // The renamed-executable case: declared and stored as a PDF, actually something else.
        byte[] windowsExecutable = new byte[ContentTypeSniffer.HEADER_BYTES];
        windowsExecutable[0] = 'M';
        windowsExecutable[1] = 'Z';

        assertThrows(ValidationException.class,
                () -> DEFAULT.checkStored("application/pdf", 1024, windowsExecutable));
    }

    /** The bucket's own measurement is what {@code checkStored} sees, so an upload that lied
     *  about its size at step 1 is still caught once the object exists. */
    @Test
    void enforcesTheSizeCapAgainstTheStoredObject() {
        assertThrows(ValidationException.class,
                () -> DEFAULT.checkStored("image/jpeg", 21L * 1024 * 1024, jpegHeader()));
        assertThrows(ValidationException.class,
                () -> DEFAULT.checkStored("image/jpeg", 0, jpegHeader()));
    }

    @Test
    void derivesTheExtensionAllowlistFromTheContentTypes() {
        assertEquals(Set.of("jpg", "jpeg", "png", "pdf"), DEFAULT.allowedExtensions());
        assertThrows(ValidationException.class,
                () -> DEFAULT.checkDeclared("scan.exe", "application/pdf", 1024));
        assertThrows(ValidationException.class,
                () -> DEFAULT.checkDeclared("noextension", "application/pdf", 1024));
    }

    /**
     * A type the sniffer has no signature for rejects every upload, because it fails closed.
     * That is correct but invisible, so it has to be reportable at configuration-load time.
     */
    @Test
    void reportsConfiguredTypesThePlatformCannotVerify() {
        MediaPolicy withSvg = DEFAULT.overriddenBy(
                List.of("application/pdf", "image/svg+xml"), null);

        assertEquals(Set.of("image/svg+xml"), withSvg.unverifiableTypes());
        assertTrue(DEFAULT.unverifiableTypes().isEmpty());
    }

    @Test
    void normalizesConfiguredTypesSoCasingAndPaddingDoNotMatter() {
        MediaPolicy policy = DEFAULT.overriddenBy(List.of("  Application/PDF  "), null);

        assertDoesNotThrow(() -> policy.checkDeclared("scan.pdf", "application/pdf", 1024));
        assertEquals(Set.of("application/pdf"), policy.allowedContentTypes());
    }

    /** An empty allowlist would be a slot that accepts nothing; that is a configuration bug,
     *  not a policy, and it must not be constructible. */
    @Test
    void refusesToBuildAPolicyThatAllowsNothing() {
        assertThrows(IllegalArgumentException.class, () -> MediaPolicy.of(List.of(), 10));
        assertThrows(IllegalArgumentException.class,
                () -> MediaPolicy.of(java.util.Arrays.asList("  ", null), 10));
    }
}
