package com.lagu.platform.search.api;

import com.lagu.platform.common.exception.PlatformException;
import com.lagu.platform.search.service.ReindexService;
import com.lagu.platform.security.DefaultPermissionEvaluator;
import com.lagu.platform.security.GatewayHeaderFilter;
import com.lagu.platform.security.PlatformSecurityContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AdminReindexControllerTest {

    private final ReindexService reindexService = mock(ReindexService.class);
    private final AdminReindexController controller = new AdminReindexController(reindexService);

    private MockedStatic<GatewayHeaderFilter> gatewayMock;

    private void asCaller(PlatformSecurityContext ctx) {
        gatewayMock = Mockito.mockStatic(GatewayHeaderFilter.class);
        gatewayMock.when(GatewayHeaderFilter::current).thenReturn(ctx);
    }

    @AfterEach
    void tearDown() {
        if (gatewayMock != null) gatewayMock.close();
    }

    @Test
    void noOrgContextThrowsCleanErrorNotNpe() {
        asCaller(PlatformSecurityContext.builder().roles(Set.of("PLATFORM_ADMIN")).orgId(null).build());

        assertThatThrownBy(() -> controller.reindex("VENUE")).isInstanceOf(PlatformException.class);
    }

    @Test
    void validContextTriggersReindex() {
        UUID orgId = UUID.randomUUID();
        asCaller(PlatformSecurityContext.builder().orgId(orgId).roles(Set.of("PLATFORM_ADMIN")).build());

        var resp = controller.reindex("VENUE");

        assertThat(resp.getStatusCode().value()).isEqualTo(202);
        verify(reindexService).reindex("VENUE", orgId.toString());
    }

    /**
     * Regression coverage for the review's finding: this endpoint's resource="*" gate was
     * defeated by DefaultPermissionEvaluator's old ORG_MANAGER branch, which checked only the
     * action shape (UPDATE) and never the resource name — so a plain ORG_MANAGER could trigger
     * an unbounded full reindex meant to be admin-only. Fixed alongside the schema-registry
     * bypass (same root cause) — see DefaultPermissionEvaluatorTest in libs/security for the
     * general-purpose coverage; this pins it down for this specific resource value too.
     */
    @Test
    void plainOrgManagerCannotPassTheWildcardResourceGate() {
        DefaultPermissionEvaluator evaluator = new DefaultPermissionEvaluator();
        PlatformSecurityContext orgManager = PlatformSecurityContext.builder()
                .userId(UUID.randomUUID()).orgId(UUID.randomUUID())
                .roles(Set.of("ORG_MANAGER")).build();

        assertThat(evaluator.canAccess(orgManager, "*", "UPDATE")).isFalse();
    }

    @Test
    void platformAdminPassesTheWildcardResourceGate() {
        DefaultPermissionEvaluator evaluator = new DefaultPermissionEvaluator();
        PlatformSecurityContext admin = PlatformSecurityContext.builder()
                .roles(Set.of("PLATFORM_ADMIN")).build();

        assertThat(evaluator.canAccess(admin, "*", "UPDATE")).isTrue();
    }
}
