package com.lagu.platform.storage;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class StorageKeysTest {

    @Test
    void buildsPrefixedKey() {
        UUID owner = UUID.randomUUID();
        String key = StorageKeys.buildPending("record", owner, "site-plan.pdf");

        // pending sits directly after the domain so a lifecycle rule can match "record/pending/"
        // as a genuine prefix of the object name — see PENDING_SEGMENT.
        assertTrue(key.startsWith("record/pending/" + owner + "/"));
        assertTrue(key.endsWith("_site-plan.pdf"));
        // The domain prefix is intact, so per-service IAM still covers pending objects.
        assertTrue(StorageKeys.isUnderDomain(key, "record"));
    }

    /**
     * Uploads land under {@code pending/} so a lifecycle rule can sweep the ones nobody ever
     * confirmed. Nothing else can share that segment, or the rule would delete real data.
     */
    @Test
    void uploadsAreBuiltPending() {
        String key = StorageKeys.buildPending("record", UUID.randomUUID(), "photo.jpg");

        assertTrue(StorageKeys.isPending(key));
        assertTrue(key.contains("/pending/"));
    }

    /** Promotion keeps the uuid and filename, so a key in a log line identifies the same object
     *  on both sides of confirm. */
    @Test
    void promotionOnlyRemovesThePendingSegment() {
        UUID owner = UUID.randomUUID();
        String pending = StorageKeys.buildPending("record", owner, "photo.jpg");
        String promoted = StorageKeys.promote(pending);

        assertFalse(StorageKeys.isPending(promoted));
        assertEquals(pending.replace("/pending/", "/"), promoted);
        assertTrue(promoted.startsWith("record/" + owner + "/"));
        assertTrue(promoted.endsWith("_photo.jpg"));
    }

    @Test
    void promotingANonPendingKeyIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> StorageKeys.promote("record/" + UUID.randomUUID() + "/abc_photo.jpg"));
    }

    /** Derivatives sit beside their original so the same prefix rules reach both. */
    @Test
    void variantKeysSitBesideTheOriginal() {
        String key = "record/abc/uuid_photo.jpg";

        assertEquals("record/abc/uuid_photo__card.jpg", StorageKeys.variantOf(key, "card"));
        // A key with no extension still gets a distinct variant rather than colliding.
        assertEquals("record/abc/uuid_photo__card", StorageKeys.variantOf("record/abc/uuid_photo", "card"));
        // A dot in a directory segment is not an extension.
        assertEquals("record/a.b/photo__card", StorageKeys.variantOf("record/a.b/photo", "card"));
    }

    @Test
    void keysAreUniquePerCall() {
        UUID owner = UUID.randomUUID();
        assertNotEquals(
                StorageKeys.buildPending("record", owner, "a.pdf"),
                StorageKeys.buildPending("record", owner, "a.pdf"));
    }

    @Test
    void rejectsMissingDomainOrOwner() {
        assertThrows(IllegalArgumentException.class,
                () -> StorageKeys.buildPending("  ", UUID.randomUUID(), "a.pdf"));
        assertThrows(IllegalArgumentException.class,
                () -> StorageKeys.buildPending("record", null, "a.pdf"));
    }

    // The whole point of the domain prefix is that per-service IAM can scope on it, so a
    // traversal attempt in the filename must not be able to climb out of it.
    @Test
    void traversalInFilenameCannotEscapeThePrefix() {
        UUID owner = UUID.randomUUID();
        String key = StorageKeys.buildPending("record", owner, "../../document/secret.pdf");

        assertTrue(key.startsWith("record/pending/" + owner + "/"));
        assertFalse(key.contains(".."));
        // domain / pending / owner / name — and nothing the filename contributed.
        assertEquals(3, key.chars().filter(c -> c == '/').count());
        assertTrue(StorageKeys.isUnderDomain(key, "record"));
        assertFalse(StorageKeys.isUnderDomain(key, "document"));
    }

    @Test
    void sanitizesSeparatorsAndUnsafeCharacters() {
        assertEquals("evil.sh", StorageKeys.sanitizeFileName("C:\\windows\\evil.sh"));
        assertEquals("a_b_c.png", StorageKeys.sanitizeFileName("a b c.png"));
        assertEquals("unnamed", StorageKeys.sanitizeFileName(null));
        assertEquals("unnamed", StorageKeys.sanitizeFileName("   "));
    }

    @Test
    void truncatesOverlongNames() {
        String name = "x".repeat(300) + ".pdf";
        assertEquals(255, StorageKeys.sanitizeFileName(name).length());
    }

    @Test
    void extractsExtension() {
        assertEquals("pdf", StorageKeys.extensionOf("a.PDF"));
        assertNull(StorageKeys.extensionOf("noextension"));
        assertNull(StorageKeys.extensionOf("trailing."));
        assertNull(StorageKeys.extensionOf(null));
    }

    @Test
    void isUnderDomainRejectsPrefixCollisions() {
        // "records/..." must not pass as domain "record".
        assertFalse(StorageKeys.isUnderDomain("records/abc/file.pdf", "record"));
        assertTrue(StorageKeys.isUnderDomain("record/abc/file.pdf", "record"));
        assertFalse(StorageKeys.isUnderDomain(null, "record"));
    }
}
