package com.lagu.platform.storage;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * These cover the check that presigned uploads would otherwise lose: with bytes going straight
 * to the bucket, this is the only thing standing between a declared content type and what the
 * object actually contains.
 */
class ContentTypeSnifferTest {

    private static byte[] header(int... bytes) {
        byte[] out = new byte[ContentTypeSniffer.HEADER_BYTES];
        for (int i = 0; i < bytes.length && i < out.length; i++) {
            out[i] = (byte) bytes[i];
        }
        return out;
    }

    /** ISO-BMFF layout: 4-byte box size, "ftyp" at offset 4, major brand at offset 8. */
    private static byte[] ftypHeader(String brand) {
        byte[] out = header(0, 0, 0, 0x20, 'f', 't', 'y', 'p');
        for (int i = 0; i < brand.length(); i++) {
            out[8 + i] = (byte) brand.charAt(i);
        }
        return out;
    }

    @Test
    void acceptsMatchingSignatures() {
        assertTrue(ContentTypeSniffer.matches(header('%', 'P', 'D', 'F'), "application/pdf"));
        assertTrue(ContentTypeSniffer.matches(
                header(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A), "image/png"));
        assertTrue(ContentTypeSniffer.matches(header(0xFF, 0xD8, 0xFF), "image/jpeg"));
        assertTrue(ContentTypeSniffer.matches(header('G', 'I', 'F', '8', '9', 'a'), "image/gif"));
    }

    /** The PNG signature's trailing CRLF/EOF bytes detect a file mangled by a text-mode
     *  transfer — matching "\x89PNG" alone would wave one through as intact. */
    @Test
    void requiresFullPngSignatureNotJustItsPrefix() {
        assertFalse(ContentTypeSniffer.matches(header(0x89, 'P', 'N', 'G'), "image/png"));
    }

    @Test
    void acceptsIsoBaseMediaFormatsByBrand() {
        assertTrue(ContentTypeSniffer.matches(ftypHeader("heic"), "image/heic"));
        assertTrue(ContentTypeSniffer.matches(ftypHeader("avif"), "image/avif"));
        assertTrue(ContentTypeSniffer.matches(ftypHeader("isom"), "video/mp4"));
        assertTrue(ContentTypeSniffer.matches(ftypHeader("qt  "), "video/quicktime"));
    }

    /** HEIC and MP4 share a byte-identical container header, so only the brand separates a
     *  photo from a video — without that check an IMAGE field would accept video. */
    @Test
    void rejectsIsoBaseMediaFileOfTheWrongBrand() {
        assertFalse(ContentTypeSniffer.matches(ftypHeader("isom"), "image/heic"));
        assertFalse(ContentTypeSniffer.matches(ftypHeader("heic"), "video/mp4"));
    }

    @Test
    void ignoresContentTypeParameters() {
        assertTrue(ContentTypeSniffer.matches(header(0xFF, 0xD8, 0xFF), "image/jpeg; charset=binary"));
    }

    /**
     * Media types are admin-configurable now, and the sniffer fails closed — so a configured
     * type it has no signature for rejects every upload rather than allowing them. Services
     * report that at config-load time, which needs this to be answerable without an upload.
     */
    @Test
    void reportsWhichTypesItCanVerify() {
        assertTrue(ContentTypeSniffer.isVerifiable("image/png"));
        assertTrue(ContentTypeSniffer.isVerifiable("IMAGE/PNG"));
        assertFalse(ContentTypeSniffer.isVerifiable("image/svg+xml"));
        assertFalse(ContentTypeSniffer.isVerifiable(null));
        assertTrue(ContentTypeSniffer.supportedTypes().contains("application/pdf"));
    }

    /** Extensions come off the same table as the signatures, so the two cannot drift apart. */
    @Test
    void derivesExtensionsFromTheSameTable() {
        assertEquals(Set.of("jpg", "jpeg"), ContentTypeSniffer.extensionsFor("image/jpeg"));
        assertTrue(ContentTypeSniffer.extensionsFor("application/zip").isEmpty());
    }

    @Test
    void acceptsWebpNonContiguousRiffHeader() {
        assertTrue(ContentTypeSniffer.matches(
                header('R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'), "image/webp"));
    }

    @Test
    void rejectsRiffContainerThatIsNotWebp() {
        // RIFF is also AVI/WAV — the WEBP marker at offset 8 is what distinguishes it.
        assertFalse(ContentTypeSniffer.matches(
                header('R', 'I', 'F', 'F', 0, 0, 0, 0, 'A', 'V', 'I', ' '), "image/webp"));
    }

    // The case that motivated keeping this check: an executable renamed to .pdf and declared
    // as application/pdf passes content-type and extension validation with nothing else left.
    @Test
    void rejectsRenamedExecutableDeclaredAsPdf() {
        assertFalse(ContentTypeSniffer.matches(header('M', 'Z', 0x90, 0x00), "application/pdf"));
        assertFalse(ContentTypeSniffer.matches(header(0x7F, 'E', 'L', 'F'), "application/pdf"));
    }

    @Test
    void rejectsMismatchedImageTypes() {
        assertFalse(ContentTypeSniffer.matches(header(0x89, 'P', 'N', 'G'), "image/jpeg"));
    }

    @Test
    void failsClosedOnUnknownOrMissingType() {
        assertFalse(ContentTypeSniffer.matches(header('%', 'P', 'D', 'F'), "application/zip"));
        assertFalse(ContentTypeSniffer.matches(header('%', 'P', 'D', 'F'), null));
        assertFalse(ContentTypeSniffer.matches(null, "application/pdf"));
    }

    @Test
    void failsClosedOnShortHeader() {
        assertFalse(ContentTypeSniffer.matches(new byte[]{'%', 'P'}, "application/pdf"));
        // WEBP needs all 12 bytes; a truncated object must not pass.
        assertFalse(ContentTypeSniffer.matches(
                Arrays.copyOf(header('R', 'I', 'F', 'F'), 8), "image/webp"));
    }

    @Test
    void isCaseInsensitiveOnDeclaredType() {
        assertTrue(ContentTypeSniffer.matches(header('%', 'P', 'D', 'F'), "APPLICATION/PDF"));
    }
}
