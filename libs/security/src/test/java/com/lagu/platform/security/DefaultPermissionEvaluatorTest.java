package com.lagu.platform.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression coverage for the schema-registry authorization gap: before this, none of
 * FieldController/FieldGroupController/ListingTypeController/SearchDefinitionController/
 * TierConfigController/TierRuleController/DocumentRequirementController carried
 * {@code @RequirePermission} on their write endpoints, so any authenticated user (any role,
 * any org) could redefine the platform's global schema. The fix added the gate; these tests
 * pin down exactly who {@link DefaultPermissionEvaluator} lets through for the resource names
 * those controllers now use.
 */
class DefaultPermissionEvaluatorTest {

    private final DefaultPermissionEvaluator evaluator = new DefaultPermissionEvaluator();

    private static final String[] SCHEMA_CONFIG_RESOURCES = {
            "ATTRIBUTE", "OBJECT_TYPE", "TIER_CONFIG", "TIER_RULE",
            "DOCUMENT_REQUIREMENT", "SEARCH_DEFINITION", "RELATIONSHIP", "WORKFLOW", "TRIGGER"
    };

    private PlatformSecurityContext ctx(String... roles) {
        return PlatformSecurityContext.builder()
                .userId(UUID.randomUUID())
                .orgId(UUID.randomUUID())
                .roles(Set.of(roles))
                .build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ATTRIBUTE", "OBJECT_TYPE", "TIER_CONFIG", "TIER_RULE",
            "DOCUMENT_REQUIREMENT", "SEARCH_DEFINITION"})
    void configAdminCanWriteEverySchemaConfigResource(String resource) {
        PlatformSecurityContext admin = ctx("CONFIG_ADMIN");
        assertThat(evaluator.canAccess(admin, resource, "CREATE")).isTrue();
        assertThat(evaluator.canAccess(admin, resource, "UPDATE")).isTrue();
        assertThat(evaluator.canAccess(admin, resource, "DELETE")).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ATTRIBUTE", "OBJECT_TYPE", "TIER_CONFIG", "TIER_RULE",
            "DOCUMENT_REQUIREMENT", "SEARCH_DEFINITION"})
    void platformAdminCanWriteEverySchemaConfigResource(String resource) {
        PlatformSecurityContext admin = ctx("PLATFORM_ADMIN");
        assertThat(evaluator.canAccess(admin, resource, "CREATE")).isTrue();
        assertThat(evaluator.canAccess(admin, resource, "DELETE")).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ATTRIBUTE", "OBJECT_TYPE", "TIER_CONFIG", "TIER_RULE",
            "DOCUMENT_REQUIREMENT", "SEARCH_DEFINITION"})
    void plainOrgManagerCannotWriteSchemaConfigResources(String resource) {
        // This is the exact regression this fix closes: before @RequirePermission was added to
        // the controllers, this role combination had no gate to fail at all.
        PlatformSecurityContext orgManager = ctx("ORG_MANAGER");
        assertThat(evaluator.canAccess(orgManager, resource, "CREATE")).isFalse();
        assertThat(evaluator.canAccess(orgManager, resource, "UPDATE")).isFalse();
        assertThat(evaluator.canAccess(orgManager, resource, "DELETE")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ATTRIBUTE", "OBJECT_TYPE", "TIER_CONFIG", "TIER_RULE",
            "DOCUMENT_REQUIREMENT", "SEARCH_DEFINITION"})
    void authenticatedUserWithNoRolesCannotWriteSchemaConfigResources(String resource) {
        PlatformSecurityContext noRoles = ctx();
        assertThat(evaluator.canAccess(noRoles, resource, "CREATE")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ATTRIBUTE", "OBJECT_TYPE", "TIER_CONFIG", "TIER_RULE",
            "DOCUMENT_REQUIREMENT", "SEARCH_DEFINITION"})
    void internalServiceCallerCannotWriteSchemaConfigResources(String resource) {
        // Internal services get READ + a narrow RECORD/RECORD_VERIFICATION grant only — they
        // must never be able to redefine schema themselves.
        PlatformSecurityContext service = PlatformSecurityContext.builder()
                .roles(Set.of(GatewayHeaderFilter.serviceRole("automation-service")))
                .build();
        assertThat(evaluator.canAccess(service, resource, "CREATE")).isFalse();
        assertThat(evaluator.canAccess(service, resource, "READ")).isTrue();
    }

    @Test
    void nullContextIsAlwaysDenied() {
        for (String resource : SCHEMA_CONFIG_RESOURCES) {
            assertThat(evaluator.canAccess(null, resource, "CREATE")).isFalse();
            assertThat(evaluator.canAccess(null, resource, "READ")).isFalse();
        }
    }

    @Test
    void unknownResourceIsDeniedEvenForConfigAdmin() {
        // isConfigResource() is an explicit allowlist — a typo'd or future resource name must
        // not silently pass just because the caller is a config admin.
        PlatformSecurityContext admin = ctx("CONFIG_ADMIN");
        assertThat(evaluator.canAccess(admin, "SOME_UNLISTED_RESOURCE", "CREATE")).isFalse();
    }

    @Test
    void orgMemberCanReadButNotWriteConfigResources() {
        PlatformSecurityContext member = ctx();
        assertThat(evaluator.canAccess(member, "ATTRIBUTE", "READ")).isTrue();
        assertThat(evaluator.canAccess(member, "ATTRIBUTE", "CREATE")).isFalse();
    }
}
