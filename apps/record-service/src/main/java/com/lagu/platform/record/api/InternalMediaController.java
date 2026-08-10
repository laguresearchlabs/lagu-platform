package com.lagu.platform.record.api;

import com.lagu.platform.common.dto.ApiResponse;
import com.lagu.platform.common.exception.ValidationException;
import com.lagu.platform.security.GatewayHeaderFilter;
import com.lagu.platform.security.PlatformSecurityContext;
import com.lagu.platform.storage.StorageKeys;
import com.lagu.platform.storage.StorageProperties;
import com.lagu.platform.storage.StorageService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Signs media keys in bulk for another platform service.
 *
 * <p>Exists for the consumer search page. Results come from listing-service's frozen snapshots,
 * each of which carries its listing's gallery — so the keys are already there, and the only
 * missing piece was turning twenty of them into URLs without twenty round trips.
 *
 * <p><b>Why listing-service does not sign them itself.</b> Every service's bucket credential is
 * IAM-conditioned to its own key prefix, which is what keeps one service out of another's
 * objects now that uploads no longer funnel through a single pod. Giving listing-service read
 * access to {@code record/} to save a hop would dissolve exactly that boundary, for a call that
 * happens once per page.
 *
 * <p><b>Why it signs caller-supplied keys rather than looking them up.</b> A snapshot is a frozen
 * approved copy — the live record may have photos that have not been through approval. Resolving
 * the cover from the record here would quietly serve those on a public page. So the caller passes
 * the key its snapshot holds, and this endpoint's job is to check that key genuinely belongs to
 * the record named alongside it before signing.
 */
@RestController
@RequestMapping("/internal/records/media")
@RequiredArgsConstructor
@Slf4j
public class InternalMediaController {

    /**
     * A page of results, with room to spare. Bounded because each entry is a signature: an
     * unbounded list would make one request as expensive as the caller cared to make it.
     */
    private static final int MAX_BATCH = 200;

    private final StorageService storage;
    private final StorageProperties storageProperties;

    @Data
    public static class SignRequest {

        @NotEmpty
        private List<Item> items;

        @Data
        public static class Item {
            /** The record the key must belong to — this is what makes the key checkable. */
            private UUID recordId;
            private String key;
        }
    }

    /**
     * Signs each key, skipping any that does not belong to the record given with it.
     *
     * <p>Skipping rather than failing the batch: one stale snapshot referencing a since-deleted
     * photo should cost that one tile, not the whole results page. The omission is visible to the
     * caller — the key is simply absent from the response — and logged here.
     */
    @PostMapping("/sign")
    public ResponseEntity<ApiResponse<Map<String, String>>> sign(
            @Valid @RequestBody SignRequest request) {
        requireInternalCaller();

        List<SignRequest.Item> items = request.getItems();
        if (items.size() > MAX_BATCH) {
            throw new ValidationException(
                    "Too many keys: " + items.size() + ", maximum is " + MAX_BATCH);
        }

        Map<String, String> urls = new LinkedHashMap<>();
        int rejected = 0;
        for (SignRequest.Item item : items) {
            if (item.getKey() == null || item.getRecordId() == null) continue;
            if (urls.containsKey(item.getKey())) continue;   // same photo named twice

            // Ownership is necessary but not sufficient. A pending key does belong to its record
            // — it is just an upload nobody has confirmed, so it has been neither scanned nor
            // verified. Signing one would serve unchecked bytes on a public page.
            if (!StorageKeys.isOwnedBy(item.getKey(), storageProperties.getDomain(), item.getRecordId())
                    || StorageKeys.isPending(item.getKey())) {
                rejected++;
                continue;
            }
            urls.put(item.getKey(),
                    storage.presignDownload(item.getKey(), storageProperties.getDownloadUrlTtl()));
        }

        if (rejected > 0) {
            // Not an error, but not nothing either: a caller sending keys that do not match their
            // records is either holding stale data or is confused about whose objects these are.
            log.warn("Refused to sign {} of {} key(s) that did not belong to the record given "
                    + "with them", rejected, items.size());
        }
        return ResponseEntity.ok(ApiResponse.ok(urls));
    }

    private void requireInternalCaller() {
        PlatformSecurityContext ctx = GatewayHeaderFilter.current();
        if (ctx == null || !ctx.isInternalService()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Internal callers only");
        }
    }
}
