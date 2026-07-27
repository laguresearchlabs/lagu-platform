package com.lagu.platform.membership;

import com.lagu.platform.common.exception.ValidationException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MembershipPolicyTest {

    private static final Set<String> MANAGER_ROLES = Set.of("OWNER", "ADMIN");

    private static class TestMember implements MembershipRecord {
        private final UUID userId;
        private final String role;
        private final boolean active;

        TestMember(UUID userId, String role, boolean active) {
            this.userId = userId;
            this.role = role;
            this.active = active;
        }

        @Override public UUID getUserId() { return userId; }
        @Override public String getRole() { return role; }
        @Override public boolean isActive() { return active; }
    }

    // ── requireNotSelf ──────────────────────────────────────────────────────

    @Test
    void requireNotSelfThrowsWhenRequesterEqualsTarget() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> MembershipPolicy.requireNotSelf(id, id))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void requireNotSelfAllowsDifferentUsers() {
        assertThatCode(() -> MembershipPolicy.requireNotSelf(UUID.randomUUID(), UUID.randomUUID()))
                .doesNotThrowAnyException();
    }

    @Test
    void requireNotSelfAllowsNullRequester() {
        assertThatCode(() -> MembershipPolicy.requireNotSelf(null, UUID.randomUUID()))
                .doesNotThrowAnyException();
    }

    // ── requireManagerRemainsAfterMutation ──────────────────────────────────

    @Test
    void removingNonManagerNeverThrows() {
        UUID target = UUID.randomUUID();
        List<TestMember> members = List.of(new TestMember(target, "MEMBER", true));

        assertThatCode(() -> MembershipPolicy.requireManagerRemainsAfterMutation(
                members, target, null, MANAGER_ROLES))
                .doesNotThrowAnyException();
    }

    @Test
    void removingSoleManagerThrows() {
        UUID target = UUID.randomUUID();
        List<TestMember> members = List.of(
                new TestMember(target, "OWNER", true),
                new TestMember(UUID.randomUUID(), "MEMBER", true));

        assertThatThrownBy(() -> MembershipPolicy.requireManagerRemainsAfterMutation(
                members, target, null, MANAGER_ROLES))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void removingOneOfTwoManagersDoesNotThrow() {
        UUID target = UUID.randomUUID();
        List<TestMember> members = List.of(
                new TestMember(target, "ADMIN", true),
                new TestMember(UUID.randomUUID(), "OWNER", true));

        assertThatCode(() -> MembershipPolicy.requireManagerRemainsAfterMutation(
                members, target, null, MANAGER_ROLES))
                .doesNotThrowAnyException();
    }

    @Test
    void demotingSoleManagerToNonManagerRoleThrows() {
        UUID target = UUID.randomUUID();
        List<TestMember> members = List.of(new TestMember(target, "ADMIN", true));

        assertThatThrownBy(() -> MembershipPolicy.requireManagerRemainsAfterMutation(
                members, target, "MEMBER", MANAGER_ROLES))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void demotingSoleManagerToAnotherManagerRoleDoesNotThrow() {
        UUID target = UUID.randomUUID();
        List<TestMember> members = List.of(new TestMember(target, "OWNER", true));

        assertThatCode(() -> MembershipPolicy.requireManagerRemainsAfterMutation(
                members, target, "ADMIN", MANAGER_ROLES))
                .doesNotThrowAnyException();
    }

    @Test
    void inactiveManagerRowsDoNotCountAsRemaining() {
        UUID target = UUID.randomUUID();
        List<TestMember> members = List.of(
                new TestMember(target, "OWNER", true),
                new TestMember(UUID.randomUUID(), "ADMIN", false)); // REMOVED, doesn't count

        assertThatThrownBy(() -> MembershipPolicy.requireManagerRemainsAfterMutation(
                members, target, null, MANAGER_ROLES))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void targetsOwnInactiveRowNeverBlocksMutation() {
        UUID target = UUID.randomUUID();
        // Target's current row isn't even active/manager, and it's the only row in the list.
        List<TestMember> members = List.of(new TestMember(target, "MEMBER", true));

        assertThatCode(() -> MembershipPolicy.requireManagerRemainsAfterMutation(
                members, target, "MEMBER", MANAGER_ROLES))
                .doesNotThrowAnyException();
    }
}
