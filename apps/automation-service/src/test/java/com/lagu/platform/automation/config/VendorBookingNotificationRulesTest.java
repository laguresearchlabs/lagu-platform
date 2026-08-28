package com.lagu.platform.automation.config;

import com.lagu.platform.automation.domain.ActionDefinition;
import com.lagu.platform.automation.domain.TriggerDefinition;
import com.lagu.platform.automation.domain.TriggerDefinitionRepository;
import com.lagu.platform.automation.model.AutomationEventContext;
import com.lagu.platform.automation.service.ConditionEvaluator;
import com.lagu.platform.automation.service.TemplateRenderer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Runs the triggers AutomationSeeder actually writes through the real ConditionEvaluator and
 * TemplateRenderer, rather than asserting on a restated copy of the rules.
 *
 * <p>Why this shape: the vendor-side rules are three loosely-coupled pieces — a field name chosen
 * in booking-service, a condition string in the seeder, and a resolver in ConditionEvaluator that
 * silently returns null for a path it does not recognise. A typo in any one of them produces a
 * trigger that never fires, with no error anywhere. Nothing in a unit test of any single class
 * would catch that; evaluating the seeded conditions against realistic contexts does.
 */
class VendorBookingNotificationRulesTest {

    private final TriggerDefinitionRepository triggerRepo = mock(TriggerDefinitionRepository.class);
    private final ConditionEvaluator conditions = new ConditionEvaluator();
    private final TemplateRenderer renderer = new TemplateRenderer();

    private final UUID consumerUserId = UUID.randomUUID();
    private final UUID vendorOwnerId = UUID.randomUUID();
    private final UUID vendorTenantId = UUID.randomUUID();

    private Map<String, TriggerDefinition> seeded;

    @BeforeEach
    void seed() {
        when(triggerRepo.findByNameAndTenantIdIsNull(any())).thenReturn(Optional.empty());
        when(triggerRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AutomationSeeder seeder = new AutomationSeeder(triggerRepo);
        ReflectionTestUtils.setField(seeder, "enabled", true);
        seeder.run(null);

        ArgumentCaptor<TriggerDefinition> saved = ArgumentCaptor.forClass(TriggerDefinition.class);
        verify(triggerRepo, atLeastOnce()).save(saved.capture());
        seeded = saved.getAllValues().stream()
                .collect(Collectors.toMap(TriggerDefinition::getName, t -> t, (a, b) -> a));
    }

    /** A booking event as AutomationEventParser would build it. */
    private AutomationEventContext ctx(String eventType, String actorSide, UUID vendorRecipient) {
        return ctx(eventType, actorSide, vendorRecipient, "bookings@venue.example");
    }

    private AutomationEventContext ctx(String eventType, String actorSide, UUID vendorRecipient,
                                       String vendorEmail) {
        Map<String, Object> data = new HashMap<>();
        data.put("consumerUserId", consumerUserId.toString());
        data.put("vendorTenantId", vendorTenantId.toString());
        data.put("eventDate", "2026-09-14");
        data.put("actorSide", actorSide);
        data.put("vendorRecipientUserId", vendorRecipient != null ? vendorRecipient.toString() : null);
        data.put("vendorRecipientEmail", vendorEmail);

        return AutomationEventContext.builder()
                .eventType(eventType)
                .tenantId(vendorTenantId)
                .recordId(UUID.randomUUID())
                .currentStatus(eventType)
                .data(data)
                .changedBy(consumerUserId)
                .build();
    }

    private TriggerDefinition vendorTrigger(String event) {
        TriggerDefinition t = seeded.get("booking_" + event.toLowerCase() + "_vendor_notification");
        assertThat(t).as("seeded vendor trigger for %s", event).isNotNull();
        return t;
    }

    private boolean fires(TriggerDefinition trigger, AutomationEventContext ctx) {
        return conditions.matches(trigger.getConditions(), ctx);
    }

    // ---- what gets seeded ----

    @Test
    void seedsAVendorTriggerForEveryEventTheVendorNeedsButNotForTheirOwnQuote() {
        assertThat(seeded).containsKeys(
                "booking_inquired_vendor_notification",
                "booking_confirmed_vendor_notification",
                "booking_cancelled_vendor_notification",
                "booking_completed_vendor_notification");
        // The vendor is the one who quotes — telling them what they just did is noise.
        assertThat(seeded).doesNotContainKey("booking_quoted_vendor_notification");
    }

    @Test
    void leavesTheExistingConsumerTriggersUnconditioned() {
        // Regression guard: the consumer side worked before this change and must keep firing on
        // every quote, including when a condition added here would have been wrong for it.
        TriggerDefinition consumerQuoted = seeded.get("booking_quoted_notification");
        assertThat(consumerQuoted).isNotNull();
        assertThat(consumerQuoted.getConditions()).isNullOrEmpty();
    }

    // ---- who gets notified ----

    @Test
    void firesForTheVendorWhenTheCustomerActs() {
        for (String event : List.of("INQUIRED", "CONFIRMED", "CANCELLED", "COMPLETED")) {
            assertThat(fires(vendorTrigger(event), ctx(event, "CONSUMER", vendorOwnerId)))
                    .as("vendor trigger for %s on a customer action", event)
                    .isTrue();
        }
    }

    @Test
    void doesNotNotifyTheVendorAboutTheirOwnAction() {
        // Both cancel and complete are open to either party. Without the actorSide condition a
        // vendor cancelling a booking would be told their booking had been cancelled.
        for (String event : List.of("CANCELLED", "COMPLETED")) {
            assertThat(fires(vendorTrigger(event), ctx(event, "VENDOR", vendorOwnerId)))
                    .as("vendor trigger for %s on the vendor's own action", event)
                    .isFalse();
        }
    }

    @Test
    void doesNotFireWhenNoRecipientCouldBeResolved() {
        // vendor-service unreachable, or the org has no active owner. The alternative — firing
        // with an empty recipient — reaches nobody but still writes a delivery attempt.
        assertThat(fires(vendorTrigger("INQUIRED"), ctx("INQUIRED", "CONSUMER", null))).isFalse();
    }

    // ---- what the notification says ----

    @Test
    void addressesTheNotificationToTheResolvedVendorUser() {
        ActionDefinition action = vendorTrigger("INQUIRED").getActions().get(0);
        Map<String, Object> rendered = renderer.renderMap(action.getConfig(), ctx("INQUIRED", "CONSUMER", vendorOwnerId));

        assertThat(rendered.get("recipientUserId")).isEqualTo(vendorOwnerId.toString());
        // Not the consumer — that is the bug this whole field exists to prevent.
        assertThat(rendered.get("recipientUserId")).isNotEqualTo(consumerUserId.toString());
    }

    @Test
    void marksVendorBookingMailTransactionalSoItCannotBeSwitchedOff() {
        for (String event : List.of("INQUIRED", "CONFIRMED", "CANCELLED", "COMPLETED")) {
            assertThat(vendorTrigger(event).getActions().get(0).getConfig())
                    .as("category for %s", event)
                    .containsEntry("category", "TRANSACTIONAL");
        }
    }

    @Test
    void sendsVendorBookingNotificationsByEmailAsWellAsInApp() {
        // The in-app row alone reaches only a vendor who happens to be in the portal — which is
        // never the vendor who most needs the nudge.
        for (String event : List.of("INQUIRED", "CONFIRMED", "CANCELLED", "COMPLETED")) {
            assertThat(vendorTrigger(event).getActions().get(0).getConfig())
                    .as("channel for %s", event)
                    .containsEntry("channel", "BOTH");
        }
    }

    @Test
    void rendersTheOrgsContactEmailIntoTheEmailRecipient() {
        ActionDefinition action = vendorTrigger("INQUIRED").getActions().get(0);
        Map<String, Object> rendered = renderer.renderMap(action.getConfig(),
                ctx("INQUIRED", "CONSUMER", vendorOwnerId, "bookings@venue.example"));

        assertThat(rendered.get("recipientEmail")).isEqualTo("bookings@venue.example");
        assertThat(rendered.get("subject")).isEqualTo(action.getConfig().get("title"));
    }

    @Test
    void stillFiresWhenTheOrgHasNoContactEmail() {
        // No IS_NOT_NULL condition on the email, unlike the recipient id: a missing address means
        // send less (in-app only), not send nothing. TemplateRenderer resolves the absent token to
        // "", which NotificationDeliveryService/EmailDeliveryService treat as "no email half".
        AutomationEventContext noEmail = ctx("INQUIRED", "CONSUMER", vendorOwnerId, null);

        assertThat(fires(vendorTrigger("INQUIRED"), noEmail)).isTrue();

        Map<String, Object> rendered = renderer.renderMap(
                vendorTrigger("INQUIRED").getActions().get(0).getConfig(), noEmail);
        assertThat(rendered.get("recipientEmail")).isEqualTo("");
        assertThat(rendered.get("recipientUserId")).isEqualTo(vendorOwnerId.toString());
    }

    @Test
    void rendersTheEventDateIntoTheMessageRatherThanLeavingATokenBehind() {
        ActionDefinition action = vendorTrigger("INQUIRED").getActions().get(0);
        Map<String, Object> rendered = renderer.renderMap(action.getConfig(), ctx("INQUIRED", "CONSUMER", vendorOwnerId));

        assertThat((String) rendered.get("message")).contains("2026-09-14").doesNotContain("{{");
    }
}
