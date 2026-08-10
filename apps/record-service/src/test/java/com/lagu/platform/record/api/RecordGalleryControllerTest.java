package com.lagu.platform.record.api;

import com.lagu.platform.common.exception.ResourceNotFoundException;
import com.lagu.platform.common.exception.ValidationException;
import com.lagu.platform.record.client.MetadataClient;
import com.lagu.platform.record.domain.Record;
import com.lagu.platform.record.domain.RecordRepository;
import com.lagu.platform.record.dto.FileUploadUrlRequest;
import com.lagu.platform.record.dto.GalleryItemConfirmRequest;
import com.lagu.platform.record.dto.GalleryItemPatchRequest;
import com.lagu.platform.record.dto.GalleryItemResponse;
import com.lagu.platform.record.dto.GalleryReorderRequest;
import com.lagu.platform.record.service.RecordService;
import com.lagu.platform.security.GatewayHeaderFilter;
import com.lagu.platform.security.PlatformSecurityContext;
import com.lagu.platform.storage.MediaIngest;
import com.lagu.platform.storage.MediaScanner;
import com.lagu.platform.storage.ObjectMeta;
import com.lagu.platform.storage.PresignedUpload;
import com.lagu.platform.storage.StorageKeys;
import com.lagu.platform.storage.StorageProperties;
import com.lagu.platform.storage.StorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
 * Galleries on records: an ordered set of photos with a cover shot.
 *
 * <p>The platform previously had no way to hold more than one image per field — {@code gallery}
 * was seeded as a MULTI_SELECT enum picker, and the upload flow wrote one key per field — while
 * the consumer card configuration already pointed at a gallery endpoint that did not exist.
 */
class RecordGalleryControllerTest {

    private final RecordRepository repository = mock(RecordRepository.class);
    private final RecordService recordService = mock(RecordService.class);
    private final MetadataClient metadataClient = mock(MetadataClient.class);
    private final StorageService storage = mock(StorageService.class);
    private final StorageProperties storageProperties = new StorageProperties();

    /** The real ingest pipeline over the mocked bucket, scanning stubbed clean. */
    private final MediaIngest mediaIngest =
            new MediaIngest(storage, (content, key) -> MediaScanner.ScanResult.ok());

    private final RecordGalleryController controller = new RecordGalleryController(
            repository, recordService, metadataClient, storage, storageProperties, mediaIngest);

    /** A real, decodable JPEG — confirm now measures and thumbnails gallery uploads, so a
     *  four-byte stub header would exercise none of that. */
    private static final byte[] REAL_JPEG = jpegBytes();

    private static byte[] jpegBytes() {
        try {
            var image = new java.awt.image.BufferedImage(800, 600,
                    java.awt.image.BufferedImage.TYPE_INT_RGB);
            var out = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(image, "jpg", out);
            return out.toByteArray();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
    private static final byte[] REAL_PDF = "%PDF-1.4\n".getBytes();
    private static final UUID RECORD_ID = UUID.randomUUID();

    private MockedStatic<GatewayHeaderFilter> gatewayMock;
    private Record record;

    @BeforeEach
    void setUp() {
        storageProperties.setDomain("record");

        gatewayMock = Mockito.mockStatic(GatewayHeaderFilter.class);
        gatewayMock.when(GatewayHeaderFilter::current).thenReturn(
                PlatformSecurityContext.builder()
                        .userId(UUID.randomUUID())
                        .tenantId(UUID.randomUUID())
                        .roles(Set.of("USER"))
                        .build());

        record = new Record();
        record.setId(RECORD_ID);
        record.setObjectType("VENUE");
        record.setData(new HashMap<>());
        when(recordService.findForContext(eq(RECORD_ID), any())).thenReturn(record);

        schemaWithRules(null);

        when(storage.presignUpload(anyString(), anyString(), any())).thenAnswer(inv ->
                new PresignedUpload("https://bucket/put", inv.getArgument(0),
                        inv.getArgument(1), Instant.now().plusSeconds(900)));
        when(storage.presignDownload(anyString(), any())).thenAnswer(inv ->
                "https://bucket/get/" + inv.getArgument(0));
        // Saving writes through to the same instance the controller re-reads, as JPA would.
        when(repository.save(any(Record.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        gatewayMock.close();
    }

    private void schemaWithRules(Map<String, Object> rules) {
        when(metadataClient.getSchema("VENUE")).thenReturn(
                new MetadataClient.ObjectTypeSchemaDto("VENUE", List.of(
                        new MetadataClient.FieldSchemaDto("gallery", "Gallery", "MEDIA_GALLERY",
                                false, false, false, false, false, null, rules, null, null),
                        new MetadataClient.FieldSchemaDto("logo", "Logo", "IMAGE",
                                false, false, false, false, false, null, null, null, null))));
    }

    /**
     * Puts content behind a pending key, as the bucket would report it after a signed PUT.
     * Confirm promotes it out of {@code pending/}, so the key the gallery ends up holding is
     * not the one uploaded to.
     */
    private String storedObject(String contentType, byte[] content) {
        String key = StorageKeys.buildPending("record", RECORD_ID, "photo.jpg");
        when(storage.stat(key)).thenReturn(
                Optional.of(new ObjectMeta(key, content.length, contentType, Instant.now())));
        when(storage.readRange(eq(key), anyInt())).thenAnswer(inv ->
                Arrays.copyOf(content, Math.min(content.length, (int) inv.getArgument(1))));
        when(storage.readAll(eq(key), anyLong())).thenReturn(content);
        return key;
    }

    private List<GalleryItemResponse> addPhoto(String caption) {
        String key = storedObject("image/jpeg", REAL_JPEG);
        GalleryItemConfirmRequest req = new GalleryItemConfirmRequest();
        req.setKey(key);
        req.setCaption(caption);
        return controller.addItem(RECORD_ID, "gallery", req).getBody().getData();
    }

    private static FileUploadUrlRequest urlRequest(String fileName, String contentType) {
        FileUploadUrlRequest r = new FileUploadUrlRequest();
        r.setFileName(fileName);
        r.setContentType(contentType);
        r.setSizeBytes(2048);
        return r;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> storedGallery() {
        return (List<Map<String, Object>>) record.getData().get("gallery");
    }

    // ── adding ────────────────────────────────────────────────────────────────

    @Test
    void addsPhotosInOrderAndStoresKeysNotUrls() {
        addPhoto("Front lawn");
        List<GalleryItemResponse> items = addPhoto("Banquet hall");

        assertThat(items).hasSize(2);
        assertThat(items).extracting(GalleryItemResponse::getCaption)
                .containsExactly("Front lawn", "Banquet hall");
        assertThat(items).extracting(GalleryItemResponse::getPosition).containsExactly(0, 1);
        assertThat(storedGallery()).allSatisfy(item ->
                assertThat(item.get("key").toString()).startsWith("record/" + RECORD_ID + "/"));
        assertThat(storedGallery().toString()).doesNotContain("http");
    }

    /** A gallery with photos always has exactly one cover, so the first upload becomes it
     *  without anyone having to ask. */
    @Test
    void theFirstPhotoBecomesTheCoverAutomatically() {
        List<GalleryItemResponse> items = addPhoto("Front lawn");

        assertThat(items).singleElement().returns(true, GalleryItemResponse::isPrimary);
    }

    @Test
    void rejectsBytesThatContradictTheDeclaredType() {
        String key = storedObject("image/jpeg", REAL_PDF);
        GalleryItemConfirmRequest req = new GalleryItemConfirmRequest();
        req.setKey(key);

        assertThatThrownBy(() -> controller.addItem(RECORD_ID, "gallery", req))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("does not match its declared type");
        verify(storage).delete(key);
        verify(repository, never()).save(any());
    }

    /** A gallery is a photo carousel, so it inherits the raster-only image rules — a PDF in one
     *  is not something a consumer page can render. */
    @Test
    void rejectsAPdfEvenThoughFileFieldsAcceptOne() {
        assertThatThrownBy(() ->
                controller.requestUploadUrl(RECORD_ID, "gallery", urlRequest("menu.pdf", "application/pdf")))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Unsupported file type");
    }

    @Test
    void rejectsAKeyBelongingToAnotherRecord() {
        GalleryItemConfirmRequest req = new GalleryItemConfirmRequest();
        req.setKey(StorageKeys.buildPending("record", UUID.randomUUID(), "photo.jpg"));

        assertThatThrownBy(() -> controller.addItem(RECORD_ID, "gallery", req))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("does not belong to record");
    }

    /** Confirming twice — a retried request, a double-clicked button — must not duplicate the
     *  photo, since the key is the same object either way. */
    @Test
    void rejectsTheSamePhotoTwice() {
        String key = storedObject("image/jpeg", REAL_JPEG);
        GalleryItemConfirmRequest req = new GalleryItemConfirmRequest();
        req.setKey(key);
        controller.addItem(RECORD_ID, "gallery", req);

        assertThatThrownBy(() -> controller.addItem(RECORD_ID, "gallery", req))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("already in the gallery");
    }

    @Test
    void rejectsANonGalleryField() {
        GalleryItemConfirmRequest req = new GalleryItemConfirmRequest();
        req.setKey(StorageKeys.buildPending("record", RECORD_ID, "photo.jpg"));

        assertThatThrownBy(() -> controller.addItem(RECORD_ID, "logo", req))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("is not a gallery field");
    }

    // ── admin-configured counts ───────────────────────────────────────────────

    @Test
    void enforcesTheAdminConfiguredMaximumCount() {
        schemaWithRules(Map.of("maxCount", 2));
        addPhoto("one");
        addPhoto("two");

        String key = storedObject("image/jpeg", REAL_JPEG);
        GalleryItemConfirmRequest req = new GalleryItemConfirmRequest();
        req.setKey(key);

        assertThatThrownBy(() -> controller.addItem(RECORD_ID, "gallery", req))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("maximum of 2 photo(s)");
    }

    /** Refused before the vendor transfers anything, so they are not told a photo is unwanted
     *  only after uploading it. */
    @Test
    void refusesAnUploadUrlWhenTheGalleryIsAlreadyFull() {
        schemaWithRules(Map.of("maxCount", 1));
        addPhoto("one");

        assertThatThrownBy(() ->
                controller.requestUploadUrl(RECORD_ID, "gallery", urlRequest("x.jpg", "image/jpeg")))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("maximum of 1 photo(s)");
        verify(storage, never()).presignUpload(anyString(), anyString(), any());
    }

    @Test
    void adminConfiguredMimeTypesNarrowWhatAGalleryAccepts() {
        schemaWithRules(Map.of("allowedMimeTypes", List.of("image/png")));

        assertThatThrownBy(() ->
                controller.requestUploadUrl(RECORD_ID, "gallery", urlRequest("x.jpg", "image/jpeg")))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Unsupported file type");
    }

    // ── captions and cover photo ──────────────────────────────────────────────

    @Test
    void promotingAPhotoDemotesThePreviousCover() {
        addPhoto("one");
        List<GalleryItemResponse> items = addPhoto("two");
        UUID second = items.get(1).getId();

        GalleryItemPatchRequest patch = new GalleryItemPatchRequest();
        patch.setPrimary(true);
        List<GalleryItemResponse> updated =
                controller.patchItem(RECORD_ID, "gallery", second, patch).getBody().getData();

        assertThat(updated).extracting(GalleryItemResponse::isPrimary).containsExactly(false, true);
    }

    /** Omitting a caption leaves the existing one alone — a client setting the cover photo
     *  should not have to resend text it never touched. */
    @Test
    void patchingOnlyTheCoverKeepsTheExistingCaption() {
        UUID id = addPhoto("Front lawn").get(0).getId();

        GalleryItemPatchRequest patch = new GalleryItemPatchRequest();
        patch.setPrimary(true);
        List<GalleryItemResponse> updated =
                controller.patchItem(RECORD_ID, "gallery", id, patch).getBody().getData();

        assertThat(updated.get(0).getCaption()).isEqualTo("Front lawn");
    }

    @Test
    void anEmptyCaptionClearsIt() {
        UUID id = addPhoto("Front lawn").get(0).getId();

        GalleryItemPatchRequest patch = new GalleryItemPatchRequest();
        patch.setCaption("");
        List<GalleryItemResponse> updated =
                controller.patchItem(RECORD_ID, "gallery", id, patch).getBody().getData();

        assertThat(updated.get(0).getCaption()).isNull();
    }

    @Test
    void rejectsAnUnknownItemId() {
        addPhoto("one");

        GalleryItemPatchRequest patch = new GalleryItemPatchRequest();
        patch.setCaption("nope");

        assertThatThrownBy(() ->
                controller.patchItem(RECORD_ID, "gallery", UUID.randomUUID(), patch))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── reordering ────────────────────────────────────────────────────────────

    @Test
    void reordersToTheRequestedSequence() {
        addPhoto("one");
        addPhoto("two");
        List<GalleryItemResponse> items = addPhoto("three");

        GalleryReorderRequest reorder = new GalleryReorderRequest();
        reorder.setItemIds(List.of(items.get(2).getId(), items.get(0).getId(), items.get(1).getId()));

        List<GalleryItemResponse> updated =
                controller.reorder(RECORD_ID, "gallery", reorder).getBody().getData();

        assertThat(updated).extracting(GalleryItemResponse::getCaption)
                .containsExactly("three", "one", "two");
        assertThat(updated).extracting(GalleryItemResponse::getPosition).containsExactly(0, 1, 2);
    }

    /** A partial list would otherwise drop the photos it omits, and a duplicate would clone one
     *  while silently losing another — so the request is checked as a set before anything moves. */
    @Test
    void rejectsAnIncompleteOrDuplicatedOrdering() {
        addPhoto("one");
        List<GalleryItemResponse> items = addPhoto("two");

        GalleryReorderRequest partial = new GalleryReorderRequest();
        partial.setItemIds(List.of(items.get(0).getId()));
        assertThatThrownBy(() -> controller.reorder(RECORD_ID, "gallery", partial))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("exactly once");

        GalleryReorderRequest duplicated = new GalleryReorderRequest();
        duplicated.setItemIds(List.of(items.get(0).getId(), items.get(0).getId()));
        assertThatThrownBy(() -> controller.reorder(RECORD_ID, "gallery", duplicated))
                .isInstanceOf(ValidationException.class);

        // Nothing was applied by either attempt.
        assertThat(storedGallery()).hasSize(2);
    }

    // ── removal ───────────────────────────────────────────────────────────────

    @Test
    void removingAPhotoDeletesItsObjectToo() {
        List<GalleryItemResponse> items = addPhoto("one");
        String key = storedGallery().get(0).get("key").toString();

        List<GalleryItemResponse> remaining =
                controller.deleteItem(RECORD_ID, "gallery", items.get(0).getId()).getBody().getData();

        assertThat(remaining).isEmpty();
        // Otherwise a photo the vendor believes is deleted stays readable to anything still
        // holding a signed URL for it.
        verify(storage).delete(key);
    }

    /** The cover cannot simply vanish when the photo holding it is removed — a search card has
     *  to render something. */
    @Test
    void removingTheCoverPromotesTheNextPhoto() {
        List<GalleryItemResponse> items = addPhoto("one");
        addPhoto("two");

        List<GalleryItemResponse> remaining =
                controller.deleteItem(RECORD_ID, "gallery", items.get(0).getId()).getBody().getData();

        assertThat(remaining).singleElement()
                .returns("two", GalleryItemResponse::getCaption)
                .returns(true, GalleryItemResponse::isPrimary);
    }

    // ── reading ───────────────────────────────────────────────────────────────

    /** One call signs the whole gallery. Per-photo signing would make a twenty-photo venue
     *  twenty round trips before anything renders. */
    @Test
    void listSignsEveryPhotoInOneCall() {
        addPhoto("one");
        addPhoto("two");

        List<GalleryItemResponse> items =
                controller.list(RECORD_ID, "gallery").getBody().getData();

        assertThat(items).hasSize(2);
        assertThat(items).allSatisfy(item ->
                assertThat(item.getUrl()).startsWith("https://bucket/get/record/" + RECORD_ID));
    }

    @Test
    void listOfAnEmptyGalleryIsEmptyRatherThanAnError() {
        assertThat(controller.list(RECORD_ID, "gallery").getBody().getData()).isEmpty();
    }

    /**
     * The field's value sits in the record's JSONB, which other write paths reach. If reading
     * trusted whatever was there, a caller who got a foreign key into the column would be handed
     * a signed URL for another record's photo.
     */
    @Test
    void listRefusesToSignAKeyBelongingToAnotherRecord() {
        List<Map<String, Object>> planted = new ArrayList<>();
        planted.add(Map.of("id", UUID.randomUUID().toString(),
                "key", "record/" + UUID.randomUUID() + "/abc_confidential",
                "isPrimary", true));
        record.getData().put("gallery", planted);

        assertThatThrownBy(() -> controller.list(RECORD_ID, "gallery"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("does not belong to record");
        verify(storage, never()).presignDownload(anyString(), any());
    }

    /** The old MULTI_SELECT "gallery" held plain strings. Those cannot be items, but they must
     *  not break the read either — the field degrades to empty. */
    @Test
    void toleratesValuesLeftBehindByTheOldMultiSelectShape() {
        record.getData().put("gallery", List.of("some-old-string", "another"));

        assertThat(controller.list(RECORD_ID, "gallery").getBody().getData()).isEmpty();
    }
}
