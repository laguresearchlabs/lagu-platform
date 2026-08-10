package com.lagu.platform.storage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checks that an object's leading bytes match its declared content type.
 *
 * <p>Content-Type and filename extension are both entirely client-supplied. Under the old
 * multipart flow {@code DocumentService} sniffed the uploaded body to catch a renamed
 * executable sent as {@code application/pdf}; with presigned direct-to-bucket uploads the
 * service never sees the payload, so that check has to be re-run against the stored object via
 * {@link StorageService#readRange} at confirm time. This class is that check, moved somewhere
 * both record-service and document-service can reach it.
 *
 * <p>Fails closed: a content type with no known signature returns false. That matters now that
 * the accepted types are admin-configurable rather than compiled in — a type nobody taught this
 * class about is not "allowed by default", it is unusable. {@link #isVerifiable} exists so a
 * service loading such a configuration can say so at startup instead of leaving admins to
 * discover it as a confirm-time rejection. Adding a format here is the one media change that
 * still needs a deploy, which is why the table is the whole of it.
 *
 * <p>The table also owns the mime→extension mapping, so the extension allowlist and the byte
 * check can never drift apart the way two hand-maintained sets would.
 */
public final class ContentTypeSniffer {

    /** Enough for every signature below — the longest reach is a 12-byte ISO-BMFF brand. */
    public static final int HEADER_BYTES = 12;

    /** One contiguous run of bytes that must appear at a fixed offset. */
    private record Part(int offset, byte[] magic) {

        boolean matches(byte[] header) {
            if (header.length < offset + magic.length) return false;
            for (int i = 0; i < magic.length; i++) {
                if (header[offset + i] != magic[i]) return false;
            }
            return true;
        }
    }

    /** One acceptable byte layout: every part must match. A format may have several. */
    private record Variant(List<Part> parts) {

        boolean matches(byte[] header) {
            return parts.stream().allMatch(p -> p.matches(header));
        }
    }

    private record Format(List<Variant> variants, Set<String> extensions) {

        boolean matches(byte[] header) {
            return variants.stream().anyMatch(v -> v.matches(header));
        }
    }

    private static final Map<String, Format> FORMATS = buildFormats();

    private ContentTypeSniffer() {
    }

    private static Map<String, Format> buildFormats() {
        Map<String, Format> formats = new LinkedHashMap<>();

        formats.put("application/pdf", format(Set.of("pdf"), prefix('%', 'P', 'D', 'F')));

        // Full 8-byte PNG signature, not just "\x89PNG": the trailing CRLF/EOF bytes are what
        // detect a PNG mangled by a text-mode transfer, which would otherwise decode as corrupt.
        formats.put("image/png", format(Set.of("png"),
                prefix(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)));

        formats.put("image/jpeg", format(Set.of("jpg", "jpeg"), prefix(0xFF, 0xD8, 0xFF)));

        // RIFF containers also carry AVI and WAV, so the brand at offset 8 is the part that
        // actually identifies WebP — matching "RIFF" alone would accept an AVI as an image.
        formats.put("image/webp", format(Set.of("webp"),
                variant(at(0, 'R', 'I', 'F', 'F'), at(8, 'W', 'E', 'B', 'P'))));

        formats.put("image/gif", format(Set.of("gif"),
                prefix('G', 'I', 'F', '8', '7', 'a'),
                prefix('G', 'I', 'F', '8', '9', 'a')));

        // ISO-BMFF family: a size field occupies bytes 0-3, "ftyp" sits at 4, and the brand at 8
        // is what separates a HEIC photo from an MP4 video — they are otherwise byte-identical
        // in the header, so brand matching is what keeps an image field from accepting video.
        formats.put("image/heic", format(Set.of("heic", "heif"),
                ftyp("heic"), ftyp("heix"), ftyp("hevc"), ftyp("heim"), ftyp("mif1"), ftyp("msf1")));

        formats.put("image/avif", format(Set.of("avif"), ftyp("avif"), ftyp("avis")));

        formats.put("video/mp4", format(Set.of("mp4", "m4v"),
                ftyp("isom"), ftyp("iso2"), ftyp("mp41"), ftyp("mp42"), ftyp("avc1"), ftyp("M4V ")));

        formats.put("video/quicktime", format(Set.of("mov"), ftyp("qt  ")));

        return Map.copyOf(formats);
    }

    /**
     * @param header      the object's first {@link #HEADER_BYTES} bytes (fewer is fine — a short
     *                    object simply fails to match)
     * @param contentType the declared type; case-insensitive
     */
    public static boolean matches(byte[] header, String contentType) {
        if (header == null || contentType == null) return false;
        Format format = FORMATS.get(normalize(contentType));
        return format != null && format.matches(header);
    }

    /**
     * Whether this class can verify {@code contentType} at all.
     *
     * <p>Callers resolving an admin-configured allowlist should check this up front: a type that
     * is not verifiable will reject every upload at confirm time, and that is far better
     * reported once at configuration-load than once per failed upload.
     */
    public static boolean isVerifiable(String contentType) {
        return contentType != null && FORMATS.containsKey(normalize(contentType));
    }

    /** Every content type this class can verify — the ceiling on what an admin can allow. */
    public static Set<String> supportedTypes() {
        return FORMATS.keySet();
    }

    /**
     * Filename extensions conventionally carrying {@code contentType}, or empty for an unknown
     * type. Derived from the same table as the byte signatures so the two cannot disagree.
     */
    public static Set<String> extensionsFor(String contentType) {
        if (contentType == null) return Set.of();
        Format format = FORMATS.get(normalize(contentType));
        return format == null ? Set.of() : format.extensions();
    }

    private static String normalize(String contentType) {
        // Browsers may append parameters ("image/jpeg; charset=binary"); the media type alone
        // is what identifies the format.
        int semicolon = contentType.indexOf(';');
        return (semicolon < 0 ? contentType : contentType.substring(0, semicolon)).trim().toLowerCase();
    }

    private static Format format(Set<String> extensions, Variant... variants) {
        return new Format(List.of(variants), extensions);
    }

    private static Variant variant(Part... parts) {
        return new Variant(List.of(parts));
    }

    private static Variant prefix(int... bytes) {
        return variant(at(0, bytes));
    }

    /** ISO-BMFF: {@code ftyp} box marker at offset 4, major brand at offset 8. */
    private static Variant ftyp(String brand) {
        return variant(at(4, 'f', 't', 'y', 'p'), at(8, chars(brand)));
    }

    private static Part at(int offset, int... bytes) {
        byte[] magic = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            magic[i] = (byte) bytes[i];
        }
        return new Part(offset, magic);
    }

    private static int[] chars(String s) {
        return s.chars().toArray();
    }
}
