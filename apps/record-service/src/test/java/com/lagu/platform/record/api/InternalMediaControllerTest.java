package com.lagu.platform.record.api;

import com.lagu.platform.common.exception.ValidationException;
import com.lagu.platform.security.GatewayHeaderFilter;
import com.lagu.platform.security.PlatformSecurityContext;
import com.lagu.platform.storage.StorageProperties;
import com.lagu.platform.storage.StorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Bulk signing for listing-service.
 *
 * <p>The endpoint signs keys the caller supplies rather than looking them up, because the caller
 * holds a frozen approved snapshot and the live record may have photos that have not been through
 * approval. That makes verifying the key against the record it arrived with the whole security
 * property here.
 */
class InternalMediaControllerTest {

    private final StorageService storage = mock(StorageService.class);
    private final StorageProperties storageProperties = new StorageProperties();
    private final InternalMediaController controller =
            new InternalMediaController(storage, storageProperties);

    private MockedStatic<GatewayHeaderFilter> gatewayMock;

    @BeforeEach
    void setUp() {
        storageProperties.setDomain("record");
        gatewayMock = Mockito.mockStatic(GatewayHeaderFilter.class);
        asInternalService();
        when(storage.presignDownload(anyString(), any()))
                .thenAnswer(inv -> "https://bucket/signed/" + inv.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        gatewayMock.close();
    }

    private void asInternalService() {
        // Internal callers are identified by a SVC_* role, granted by GatewayHeaderFilter once
        // the X-Internal-Service header and gateway secret check out.
        gatewayMock.when(GatewayHeaderFilter::current).thenReturn(
                PlatformSecurityContext.builder()
                        .userId(UUID.randomUUID())
                        .roles(Set.of(GatewayHeaderFilter.SERVICE_ROLE_PREFIX + "LISTING_SERVICE"))
                        .build());
    }

    private void asOrdinaryUser() {
        gatewayMock.when(GatewayHeaderFilter::current).thenReturn(
                PlatformSecurityContext.builder()
                        .userId(UUID.randomUUID())
                        .tenantId(UUID.randomUUID())
                        .roles(Set.of("USER"))
                        .build());
    }

    private static InternalMediaController.SignRequest request(Map<UUID, String> keysByRecord) {
        List<InternalMediaController.SignRequest.Item> items = new ArrayList<>();
        keysByRecord.forEach((recordId, key) -> {
            var item = new InternalMediaController.SignRequest.Item();
            item.setRecordId(recordId);
            item.setKey(key);
            items.add(item);
        });
        var req = new InternalMediaController.SignRequest();
        req.setItems(items);
        return req;
    }

    @Test
    void signsEveryKeyThatBelongsToItsRecord() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        var response = controller.sign(request(Map.of(
                a, "record/" + a + "/1_x__card.jpg",
                b, "record/" + b + "/2_y__card.jpg")));

        Map<String, String> urls = response.getBody().getData();
        assertThat(urls).hasSize(2);
        assertThat(urls.get("record/" + a + "/1_x__card.jpg")).startsWith("https://bucket/signed/");
    }

    /**
     * The check that makes caller-supplied keys safe. Without it, an internal caller — or anything
     * that got hold of internal credentials — could name any object under {@code record/} and be
     * handed a URL for it.
     */
    @Test
    void refusesAKeyThatDoesNotBelongToTheRecordGivenWithIt() {
        UUID claimed = UUID.randomUUID();
        UUID other = UUID.randomUUID();

        var response = controller.sign(request(Map.of(
                claimed, "record/" + other + "/1_confidential.jpg")));

        assertThat(response.getBody().getData()).isEmpty();
        verify(storage, never()).presignDownload(anyString(), any());
    }

    /** One bad key must not cost the page its other photos. */
    @Test
    void skipsOnlyTheOffendingKey() {
        UUID good = UUID.randomUUID();
        UUID bad = UUID.randomUUID();

        var response = controller.sign(request(Map.of(
                good, "record/" + good + "/1_x.jpg",
                bad, "record/" + UUID.randomUUID() + "/2_y.jpg")));

        assertThat(response.getBody().getData()).containsOnlyKeys("record/" + good + "/1_x.jpg");
    }

    /** Pending objects are unverified and mid-flight; nothing should be serving them. */
    @Test
    void refusesToSignSomethingStillAwaitingConfirmation() {
        UUID id = UUID.randomUUID();

        var response = controller.sign(request(Map.of(
                id, "record/pending/" + id + "/1_x.jpg")));

        // isOwnedBy accepts both layouts, so this is worth stating: a pending key does belong to
        // the record, and signing it would hand out an object that has not been scanned yet.
        assertThat(response.getBody().getData()).isEmpty();
    }

    @Test
    void rejectsAnOversizedBatch() {
        Map<UUID, String> many = new java.util.LinkedHashMap<>();
        for (int i = 0; i < 250; i++) {
            UUID id = UUID.randomUUID();
            many.put(id, "record/" + id + "/" + i + "_x.jpg");
        }

        assertThatThrownBy(() -> controller.sign(request(many)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("maximum is 200");
    }

    @Test
    void isClosedToOrdinaryCallers() {
        asOrdinaryUser();
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> controller.sign(request(Map.of(id, "record/" + id + "/1_x.jpg"))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Internal callers only");
    }
}
