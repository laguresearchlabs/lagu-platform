package com.lagu.platform.search.api;

import com.lagu.platform.common.exception.PlatformException;
import com.lagu.platform.security.GatewayHeaderFilter;
import com.lagu.platform.security.PlatformSecurityContext;
import com.lagu.platform.search.service.ReindexService;
import com.lagu.platform.security.RequirePermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/admin/reindex")
@RequiredArgsConstructor
@Tag(name = "Admin", description = "Administrative reindex operations")
public class AdminReindexController {

    private final ReindexService reindexService;

    @PostMapping("/{objectType}")
    @Operation(summary = "Trigger a full reindex of all records for the given objectType")
    @RequirePermission(resource = "*", action = "UPDATE")
    public ResponseEntity<Map<String, String>> reindex(@PathVariable String objectType) {
        PlatformSecurityContext ctx = GatewayHeaderFilter.current();
        if (ctx == null || ctx.getOrgId() == null) {
            throw new PlatformException("ORG_CONTEXT_REQUIRED",
                    "An organization context is required to reindex", HttpStatus.FORBIDDEN);
        }
        String orgId = ctx.getOrgId().toString();
        reindexService.reindex(objectType, orgId);
        return ResponseEntity.accepted()
                .body(Map.of("status", "REINDEX_STARTED", "objectType", objectType, "orgId", orgId));
    }
}
