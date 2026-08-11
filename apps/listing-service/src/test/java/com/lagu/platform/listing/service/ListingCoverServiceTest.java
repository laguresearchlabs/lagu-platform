package com.lagu.platform.listing.service;

import com.lagu.platform.listing.client.RecordServiceClient;
import com.lagu.platform.listing.domain.ListingSnapshot;
import com.lagu.platform.listing.domain.ListingSnapshotRepository;
import com.lagu.platform.security.GatewayHeaderFilter;
import com.lagu.platform.security.PlatformSecurityContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * Cover photos for a results page.
 *
 * <p>Two properties matter more than the mechanics: the photos come from the frozen snapshot
 * rather than the live record, so nothing unapproved reaches a public page; and the same
 * visibility rule as the snapshot endpoint applies, so this cannot become a way to read an
 * unpublished vendor's imagery by id.
 */
class ListingCoverServiceTest {

    private final ListingSnapshotRepository repository = mock(ListingSnapshotRepository.class);
    private final RecordServiceClient recordServiceClient = mock(RecordServiceClient.class);
    private final ListingCoverService service =
            new ListingCoverService(repository, recordServiceClient);

    private MockedStatic<GatewayHeaderFilter> gatewayMock;
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        gatewayMock = Mockito.mockStatic(GatewayHeaderFilter.class);
        asAnonymousConsumer();
        // Echo a signed URL per key, as record-service would.
        when(recordServiceClient.signMediaKeys(any())).thenAnswer(inv -> {
            java.util.Collection<RecordServiceClient.MediaKey> keys = inv.getArgument(0);
            Map<String, String> urls = new HashMap<>();
            keys.forEach(k -> urls.put(k.key(), "https://bucket/signed/" + k.key()));
            return urls;
        });
    }

    @AfterEach
    void tearDown() {
        gatewayMock.close();
    }

    private void asAnonymousConsumer() {
        gatewayMock.when(GatewayHeaderFilter::current).thenReturn(null);
    }

    private void asTenantUser(UUID callerTenantId) {
        gatewayMock.when(GatewayHeaderFilter::current).thenReturn(
                PlatformSecurityContext.builder()
                        .userId(UUID.randomUUID())
                        .tenantId(callerTenantId)
                        .roles(Set.of("USER"))
                        .build());
    }

    private ListingSnapshot snapshot(UUID recordId, String status, Object gallery) {
        ListingSnapshot snap = new ListingSnapshot();
        snap.setId(UUID.randomUUID());
        snap.setRecordId(recordId);
        snap.setTenantId(tenantId);
        snap.setObjectType("VENUE");
        snap.setStatus(status);
        Map<String, Object> data = new HashMap<>();
        if (gallery != null) data.put("gallery", gallery);
        snap.setData(data);
        return snap;
    }

    private static Map<String, Object> item(String key, boolean primary, String caption, String cardKey) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", UUID.randomUUID().toString());
        map.put("key", key);
        map.put("isPrimary", primary);
        map.put("caption", caption);
        map.put("cardKey", cardKey);
        return map;
    }

    @Test
    void returnsTheCoverPhotoForEachListing() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(repository.findByRecordIdIn(anyList())).thenReturn(List.of(
                snapshot(a, "PUBLISHED", List.of(
                        item("record/" + a + "/1_x.jpg", false, "side", "record/" + a + "/1_x__card.jpg"),
                        item("record/" + a + "/2_x.jpg", true, "front", "record/" + a + "/2_x__card.jpg"))),
                snapshot(b, "PUBLISHED", List.of(
                        item("record/" + b + "/9_x.jpg", true, "hall", "record/" + b + "/9_x__card.jpg")))));

        Map<UUID, ListingCoverService.Cover> covers = service.coversFor(List.of(a, b));

        assertThat(covers).hasSize(2);
        assertThat(covers.get(a).getCaption()).isEqualTo("front");
        assertThat(covers.get(a).getThumbnailUrl()).endsWith("2_x__card.jpg");
        assertThat(covers.get(b).getCaption()).isEqualTo("hall");
    }

    /** The whole point of the batch: one signing call for the page, not one per listing. */
    @Test
    void signsEveryCoverInASingleCall() {
        List<UUID> ids = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        when(repository.findByRecordIdIn(anyList())).thenReturn(ids.stream()
                .map(id -> snapshot(id, "PUBLISHED",
                        List.of(item("record/" + id + "/1_x.jpg", true, null, null))))
                .toList());

        service.coversFor(ids);

        verify(recordServiceClient, times(1)).signMediaKeys(any());
        verify(repository, times(1)).findByRecordIdIn(anyList());
    }

    /** Falls back to the original when a photo has no derivative — a format the platform could
     *  not thumbnail still renders, at the cost of bandwidth. */
    @Test
    void usesTheOriginalWhenThereIsNoThumbnail() {
        UUID id = UUID.randomUUID();
        when(repository.findByRecordIdIn(anyList())).thenReturn(List.of(
                snapshot(id, "PUBLISHED", List.of(item("record/" + id + "/1_x.heic", true, null, null)))));

        assertThat(service.coversFor(List.of(id)).get(id).getThumbnailUrl())
                .endsWith("1_x.heic");
    }

    // ── visibility ────────────────────────────────────────────────────────────

    @Test
    void anUnpublishedListingHasNoCoverForAnonymousCallers() {
        UUID id = UUID.randomUUID();
        when(repository.findByRecordIdIn(anyList())).thenReturn(List.of(
                snapshot(id, "UNPUBLISHED", List.of(item("record/" + id + "/1_x.jpg", true, null, null)))));

        assertThat(service.coversFor(List.of(id))).isEmpty();
        // Nothing is even signed for it.
        verify(recordServiceClient).signMediaKeys(argThat(java.util.Collection::isEmpty));
    }

    @Test
    void theOwningOrgStillSeesItsOwnUnpublishedCover() {
        UUID id = UUID.randomUUID();
        asTenantUser(tenantId);
        when(repository.findByRecordIdIn(anyList())).thenReturn(List.of(
                snapshot(id, "UNPUBLISHED", List.of(item("record/" + id + "/1_x.jpg", true, null, null)))));

        assertThat(service.coversFor(List.of(id))).containsKey(id);
    }

    @Test
    void anotherOrgDoesNotSeeAnUnpublishedCover() {
        UUID id = UUID.randomUUID();
        asTenantUser(UUID.randomUUID());
        when(repository.findByRecordIdIn(anyList())).thenReturn(List.of(
                snapshot(id, "UNPUBLISHED", List.of(item("record/" + id + "/1_x.jpg", true, null, null)))));

        assertThat(service.coversFor(List.of(id))).isEmpty();
    }

    // ── degradation ───────────────────────────────────────────────────────────

    /** Snapshots are frozen at some earlier schema version and written by another service, so a
     *  gallery that cannot be read costs one tile its photo rather than failing the page. */
    @Test
    void aMalformedGalleryCostsOnlyThatListingItsPhoto() {
        UUID good = UUID.randomUUID();
        UUID bad = UUID.randomUUID();
        when(repository.findByRecordIdIn(anyList())).thenReturn(List.of(
                snapshot(bad, "PUBLISHED", List.of(Map.of("id", "no-key-here"))),
                snapshot(good, "PUBLISHED",
                        List.of(item("record/" + good + "/1_x.jpg", true, null, null)))));

        Map<UUID, ListingCoverService.Cover> covers = service.coversFor(List.of(good, bad));

        assertThat(covers).containsOnlyKeys(good);
    }

    @Test
    void listingsWithNoGalleryAreSimplyAbsent() {
        UUID id = UUID.randomUUID();
        when(repository.findByRecordIdIn(anyList()))
                .thenReturn(List.of(snapshot(id, "PUBLISHED", null)));

        assertThat(service.coversFor(List.of(id))).isEmpty();
    }

    /** A key the signer refuses — a stale snapshot naming a deleted photo — drops that tile's
     *  photo rather than the whole response. */
    @Test
    void aKeyThatCannotBeSignedIsOmitted() {
        UUID id = UUID.randomUUID();
        when(repository.findByRecordIdIn(anyList())).thenReturn(List.of(
                snapshot(id, "PUBLISHED", List.of(item("record/" + id + "/1_x.jpg", true, null, null)))));
        // doReturn, not when(...): re-stubbing with when() would invoke the setUp answer with a
        // null argument first, and that answer reads the map it is given.
        doReturn(Map.of()).when(recordServiceClient).signMediaKeys(any());

        assertThat(service.coversFor(List.of(id))).isEmpty();
    }

    // ── detail page carousel ──────────────────────────────────────────────────

    /** The public counterpart to record-service's gallery endpoint, which an anonymous consumer
     *  cannot reach because it requires RECORD:READ. */
    @Test
    void returnsEveryPhotoInOrderForOneListing() {
        UUID id = UUID.randomUUID();
        when(repository.findByRecordId(id)).thenReturn(java.util.Optional.of(
                snapshot(id, "PUBLISHED", List.of(
                        item("record/" + id + "/1_a.jpg", false, "side", "record/" + id + "/1_a__card.jpg"),
                        item("record/" + id + "/2_b.jpg", true, "front", "record/" + id + "/2_b__card.jpg")))));

        List<ListingCoverService.Photo> photos = service.photosFor(id, null);

        assertThat(photos).hasSize(2);
        assertThat(photos).extracting(ListingCoverService.Photo::getCaption)
                .containsExactly("side", "front");
        assertThat(photos).extracting(ListingCoverService.Photo::getPosition).containsExactly(0, 1);
        assertThat(photos.get(1).isPrimary()).isTrue();
        // Display URL is the full derivative; the thumbnail is the card one.
        assertThat(photos.get(1).getUrl()).endsWith("2_b.jpg");
        assertThat(photos.get(1).getThumbnailUrl()).endsWith("2_b__card.jpg");
    }

    /** One signing call for the whole carousel, not one per photo. */
    @Test
    void signsTheWholeCarouselInOneCall() {
        UUID id = UUID.randomUUID();
        when(repository.findByRecordId(id)).thenReturn(java.util.Optional.of(
                snapshot(id, "PUBLISHED", List.of(
                        item("record/" + id + "/1_a.jpg", true, null, null),
                        item("record/" + id + "/2_b.jpg", false, null, null),
                        item("record/" + id + "/3_c.jpg", false, null, null)))));

        service.photosFor(id, null);

        verify(recordServiceClient, times(1)).signMediaKeys(any());
    }

    /**
     * Every key must travel with the record it belongs to — that pairing is what record-service
     * verifies before signing. A carousel signs many keys for one record, which is why the
     * contract is a list of pairs rather than a map keyed by record id.
     */
    @Test
    void everyKeySentCarriesTheOwningRecordId() {
        UUID id = UUID.randomUUID();
        when(repository.findByRecordId(id)).thenReturn(java.util.Optional.of(
                snapshot(id, "PUBLISHED", List.of(
                        item("record/" + id + "/1_a.jpg", true, null, "record/" + id + "/1_a__card.jpg")))));

        service.photosFor(id, null);

        verify(recordServiceClient).signMediaKeys(argThat(keys ->
                keys.stream().allMatch(k -> id.equals(k.recordId()))
                        && keys.size() == 2));   // full + card for the one photo
    }

    @Test
    void anUnpublishedListingsCarouselIsEmptyForAnonymousCallers() {
        UUID id = UUID.randomUUID();
        when(repository.findByRecordId(id)).thenReturn(java.util.Optional.of(
                snapshot(id, "UNPUBLISHED", List.of(item("record/" + id + "/1_a.jpg", true, null, null)))));

        assertThat(service.photosFor(id, null)).isEmpty();
        verifyNoInteractions(recordServiceClient);
    }

    /** Absent and not-visible look identical, so this cannot be used to probe for unpublished
     *  listings by id. */
    @Test
    void anUnknownListingIsEmptyRatherThanAnError() {
        UUID id = UUID.randomUUID();
        when(repository.findByRecordId(id)).thenReturn(java.util.Optional.empty());

        assertThat(service.photosFor(id, null)).isEmpty();
    }

    @Test
    void aPhotoThatCannotBeSignedIsDroppedNotRenderedBroken() {
        UUID id = UUID.randomUUID();
        when(repository.findByRecordId(id)).thenReturn(java.util.Optional.of(
                snapshot(id, "PUBLISHED", List.of(item("record/" + id + "/1_a.jpg", true, null, null)))));
        doReturn(Map.of()).when(recordServiceClient).signMediaKeys(any());

        assertThat(service.photosFor(id, null)).isEmpty();
    }

    @Test
    void anEmptyRequestDoesNoWorkAtAll() {
        assertThat(service.coversFor(List.of())).isEmpty();
        verifyNoInteractions(repository, recordServiceClient);
    }
}
