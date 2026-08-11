package com.lagu.platform.event.service;

import com.lagu.platform.common.exception.ValidationException;
import com.lagu.platform.event.domain.EventPhoto;
import com.lagu.platform.event.domain.EventPhotoRepository;
import com.lagu.platform.storage.MediaIngest;
import com.lagu.platform.storage.StorageKeys;
import com.lagu.platform.storage.StorageProperties;
import com.lagu.platform.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * The event photo album.
 *
 * <p>This exists because an Event is not a record — event posts got their photos for free by
 * being EVENT_POST records with a MEDIA_GALLERY field, and an event has nowhere else to put them.
 * What matters here is that keys are scoped to the event, that a rejected upload leaves nothing
 * behind, and that PUBLIC/PRIVATE is enforced rather than trusted.
 */
class EventPhotoServiceTest {

    private final EventPhotoRepository repository = mock(EventPhotoRepository.class);
    private final StorageService storage = mock(StorageService.class);
    private final MediaIngest mediaIngest = mock(MediaIngest.class);
    private final StorageProperties storageProperties = new StorageProperties();

    private final EventPhotoService service =
            new EventPhotoService(repository, storage, storageProperties, mediaIngest);

    private final UUID eventId = UUID.randomUUID();
    private final UUID uploaderId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        storageProperties.setDomain("event");
        when(repository.save(any(EventPhoto.class))).thenAnswer(inv -> inv.getArgument(0));
        when(storage.presignDownload(anyString(), any()))
                .thenAnswer(inv -> "https://bucket/signed/" + inv.getArgument(0));
    }

    private String pendingKey() {
        return StorageKeys.buildPending("event", eventId, "photo.jpg");
    }

    private void stubIngest(String pending, String cardKey) {
        when(mediaIngest.confirm(any())).thenReturn(MediaIngest.Result.builder()
                .key(StorageKeys.promote(pending))
                .contentType("image/jpeg")
                .sizeBytes(2048)
                .variantKeys(cardKey == null ? Map.of() : Map.of(MediaIngest.CARD_VARIANT, cardKey))
                .build());
    }

    @Test
    void confirmAddsThePhotoAndStoresKeysNotUrls() {
        String pending = pendingKey();
        String promoted = StorageKeys.promote(pending);
        stubIngest(pending, promoted + "__card");

        var response = service.confirmUpload(eventId, uploaderId, pending, "PUBLIC", "Front lawn");

        assertThat(response.getCaption()).isEqualTo("Front lawn");
        assertThat(response.getUrl()).startsWith("https://bucket/signed/");

        var saved = org.mockito.ArgumentCaptor.forClass(EventPhoto.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getStorageKey()).isEqualTo(promoted);
        assertThat(saved.getValue().getStorageKey()).doesNotContain("/pending/");
        assertThat(saved.getValue().getStorageKey()).doesNotContain("http");
    }

    /** A member of one event must not be able to adopt another event's object. */
    @Test
    void rejectsAKeyBelongingToAnotherEvent() {
        String foreign = StorageKeys.buildPending("event", UUID.randomUUID(), "photo.jpg");

        assertThatThrownBy(() -> service.confirmUpload(eventId, uploaderId, foreign, "PUBLIC", null))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("does not belong to event");
        verifyNoInteractions(mediaIngest);
    }

    /** Confirming an already-durable key would re-adopt an object that is already referenced. */
    @Test
    void rejectsAKeyThatIsNotAwaitingConfirmation() {
        String durable = "event/" + eventId + "/abc_photo.jpg";

        assertThatThrownBy(() -> service.confirmUpload(eventId, uploaderId, durable, "PUBLIC", null))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("awaiting confirmation");
    }

    @Test
    void defaultsToPublicAndRejectsAnythingElse() {
        String pending = pendingKey();
        stubIngest(pending, null);

        assertThat(service.confirmUpload(eventId, uploaderId, pending, null, null).getVisibility())
                .isEqualTo("PUBLIC");

        assertThatThrownBy(() -> service.confirmUpload(eventId, uploaderId, pending, "SECRET", null))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("must be PUBLIC or PRIVATE");
    }

    /** A format the platform cannot thumbnail still uploads — the original is served instead. */
    @Test
    void aPhotoWithNoDerivativeFallsBackToTheOriginal() {
        String pending = pendingKey();
        String promoted = StorageKeys.promote(pending);
        stubIngest(pending, null);

        var response = service.confirmUpload(eventId, uploaderId, pending, "PUBLIC", null);

        assertThat(response.getThumbnailUrl()).isEqualTo("https://bucket/signed/" + promoted);
    }

    @Test
    void deletingAPhotoRemovesItsObjectsToo() {
        EventPhoto photo = new EventPhoto();
        photo.setId(UUID.randomUUID());
        photo.setEventId(eventId);
        photo.setStorageKey("event/" + eventId + "/1_a.jpg");
        photo.setCardKey("event/" + eventId + "/1_a__card.jpg");
        when(repository.findByIdAndEventId(photo.getId(), eventId)).thenReturn(Optional.of(photo));

        service.delete(eventId, photo.getId());

        verify(repository).delete(photo);
        verify(storage).delete("event/" + eventId + "/1_a.jpg");
        // The derivative goes too — it is meaningless alone and would stay renderable to anyone
        // holding a signed thumbnail URL.
        verify(storage).delete("event/" + eventId + "/1_a__card.jpg");
    }

    /**
     * Row first, object second. A failed storage delete leaves an orphan, which is recoverable;
     * the other order would leave a row pointing at bytes that are already gone.
     */
    @Test
    void aFailedObjectDeleteStillRemovesThePhoto() {
        EventPhoto photo = new EventPhoto();
        photo.setId(UUID.randomUUID());
        photo.setEventId(eventId);
        photo.setStorageKey("event/" + eventId + "/1_a.jpg");
        when(repository.findByIdAndEventId(photo.getId(), eventId)).thenReturn(Optional.of(photo));
        doThrow(new com.lagu.platform.storage.StorageException("bucket down"))
                .when(storage).delete(anyString());

        service.delete(eventId, photo.getId());   // must not throw

        verify(repository).delete(photo);
    }

    @Test
    void listingByVisibilityAsksTheRepositoryForThatVisibilityOnly() {
        when(repository.findByEventIdAndVisibilityOrderByCreatedAtDesc(eventId, "PUBLIC"))
                .thenReturn(List.of());

        service.list(eventId, "public");

        // Normalised, so a caller passing lowercase does not silently match nothing.
        verify(repository).findByEventIdAndVisibilityOrderByCreatedAtDesc(eventId, "PUBLIC");
    }
}
