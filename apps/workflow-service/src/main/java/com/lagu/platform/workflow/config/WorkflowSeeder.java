package com.lagu.platform.workflow.config;

import com.lagu.platform.workflow.domain.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class WorkflowSeeder implements ApplicationRunner {

    private final WorkflowDefinitionRepository wfRepo;

    @Value("${platform.seeder.enabled:true}")
    private boolean enabled;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled) return;
        log.info("Running WorkflowSeeder...");
        seedVendorReviewWorkflows();
        seedEventLifecycleWorkflows();
        log.info("WorkflowSeeder complete");
    }

    // ── Vendor review — shared structure for all vendor types ─────────────────

    private void seedVendorReviewWorkflows() {
        for (String objectType : List.of("VENUE", "PHOTOGRAPHER", "CATERER", "DECORATOR", "MAKEUP_ARTIST")) {
            if (!wfRepo.findForObjectType(objectType, null).isEmpty()) continue;

            WorkflowDefinition wf = new WorkflowDefinition();
            wf.setName("vendor_review_" + objectType.toLowerCase());
            wf.setLabel("Vendor Review — " + titleCase(objectType));
            wf.setObjectType(objectType);
            wf.setInitialStatus("DRAFT");
            wf.setActive(true);

            List<WorkflowState> states = new ArrayList<>();
            states.add(state(wf, "DRAFT",        "Draft",        "#9E9E9E", false, 0));
            states.add(state(wf, "SUBMITTED",     "Submitted",    "#2196F3", false, 1));
            states.add(state(wf, "UNDER_REVIEW",  "Under Review", "#FF9800", false, 2));
            states.add(state(wf, "APPROVED",      "Approved",     "#4CAF50", false, 3));
            states.add(state(wf, "REJECTED",      "Rejected",     "#F44336", false, 4));
            states.add(state(wf, "PUBLISHED",     "Published",    "#00BCD4", false, 5));
            states.add(state(wf, "SUSPENDED",     "Suspended",    "#FF5722", false, 6));
            states.add(state(wf, "ARCHIVED",      "Archived",     "#607D8B", true,  7));
            wf.setStates(states);

            List<WorkflowTransition> transitions = new ArrayList<>();
            List<String> vendorRoles  = List.of("ORG_OWNER", "ORG_MANAGER");
            List<String> reviewRoles  = List.of("CONFIG_ADMIN", "PLATFORM_ADMIN");
            List<String> ownerOnly    = List.of("ORG_OWNER");

            transitions.add(tx(wf, "DRAFT",       "SUBMITTED",    "submit",     "Submit for Review",  vendorRoles));
            transitions.add(tx(wf, "SUBMITTED",    "DRAFT",        "withdraw",   "Withdraw",           ownerOnly));
            transitions.add(tx(wf, "SUBMITTED",    "UNDER_REVIEW", "start_review","Start Review",     reviewRoles));
            transitions.add(tx(wf, "UNDER_REVIEW", "APPROVED",     "approve",    "Approve",           reviewRoles));
            transitions.add(tx(wf, "UNDER_REVIEW", "REJECTED",     "reject",     "Reject",            reviewRoles));
            transitions.add(tx(wf, "REJECTED",     "SUBMITTED",    "resubmit",   "Resubmit",          vendorRoles));
            transitions.add(tx(wf, "APPROVED",     "PUBLISHED",    "publish",    "Publish",           reviewRoles));
            transitions.add(tx(wf, "PUBLISHED",    "SUSPENDED",    "suspend",    "Suspend",           reviewRoles));
            transitions.add(tx(wf, "SUSPENDED",    "PUBLISHED",    "reinstate",  "Reinstate",         reviewRoles));
            transitions.add(tx(wf, "PUBLISHED",    "ARCHIVED",     "archive",    "Archive",           reviewRoles));
            transitions.add(tx(wf, "SUSPENDED",    "ARCHIVED",     "archive_suspended","Archive",     reviewRoles));
            wf.setTransitions(transitions);

            wfRepo.save(wf);
            log.info("Seeded vendor review workflow for {}", objectType);
        }
    }

    // ── Event lifecycle workflows ─────────────────────────────────────────────

    private void seedEventLifecycleWorkflows() {
        seedWeddingEventWorkflow();
        seedCorporateEventWorkflow();
    }

    private void seedWeddingEventWorkflow() {
        if (!wfRepo.findForObjectType("WEDDING_EVENT", null).isEmpty()) return;

        WorkflowDefinition wf = new WorkflowDefinition();
        wf.setName("event_lifecycle_wedding");
        wf.setLabel("Wedding Event Lifecycle");
        wf.setObjectType("WEDDING_EVENT");
        wf.setInitialStatus("PLANNING");
        wf.setActive(true);

        List<WorkflowState> states = new ArrayList<>();
        states.add(state(wf, "PLANNING",    "Planning",     "#2196F3", false, 0));
        states.add(state(wf, "CONFIRMED",   "Confirmed",    "#4CAF50", false, 1));
        states.add(state(wf, "IN_PROGRESS", "In Progress",  "#FF9800", false, 2));
        states.add(state(wf, "COMPLETED",   "Completed",    "#00BCD4", true,  3));
        states.add(state(wf, "CANCELLED",   "Cancelled",    "#F44336", true,  4));
        states.add(state(wf, "ARCHIVED",    "Archived",     "#607D8B", true,  5));
        wf.setStates(states);

        List<String> orgRoles  = List.of("ORG_OWNER", "ORG_MANAGER");
        List<String> ownerOnly = List.of("ORG_OWNER");

        List<WorkflowTransition> transitions = new ArrayList<>();
        transitions.add(tx(wf, "PLANNING",    "CONFIRMED",   "confirm",  "Confirm Event",   orgRoles));
        transitions.add(tx(wf, "PLANNING",    "CANCELLED",   "cancel",   "Cancel",          ownerOnly));
        transitions.add(tx(wf, "CONFIRMED",   "IN_PROGRESS", "start",    "Start Event",     orgRoles));
        transitions.add(tx(wf, "CONFIRMED",   "CANCELLED",   "cancel",   "Cancel",          ownerOnly));
        transitions.add(tx(wf, "IN_PROGRESS", "COMPLETED",   "complete", "Mark Complete",   orgRoles));
        transitions.add(tx(wf, "COMPLETED",   "ARCHIVED",    "archive",  "Archive",         orgRoles));
        transitions.add(tx(wf, "CANCELLED",   "ARCHIVED",    "archive",  "Archive",         orgRoles));
        wf.setTransitions(transitions);

        wfRepo.save(wf);
        log.info("Seeded WEDDING_EVENT workflow");
    }

    private void seedCorporateEventWorkflow() {
        if (!wfRepo.findForObjectType("CORPORATE_EVENT", null).isEmpty()) return;

        WorkflowDefinition wf = new WorkflowDefinition();
        wf.setName("event_lifecycle_corporate");
        wf.setLabel("Corporate Event Lifecycle");
        wf.setObjectType("CORPORATE_EVENT");
        wf.setInitialStatus("PLANNING");
        wf.setActive(true);

        List<WorkflowState> states = new ArrayList<>();
        states.add(state(wf, "PLANNING",    "Planning",     "#2196F3", false, 0));
        states.add(state(wf, "SUBMITTED",   "Submitted",    "#9C27B0", false, 1));
        states.add(state(wf, "CONFIRMED",   "Confirmed",    "#4CAF50", false, 2));
        states.add(state(wf, "IN_PROGRESS", "In Progress",  "#FF9800", false, 3));
        states.add(state(wf, "COMPLETED",   "Completed",    "#00BCD4", true,  4));
        states.add(state(wf, "CANCELLED",   "Cancelled",    "#F44336", true,  5));
        wf.setStates(states);

        List<String> orgRoles    = List.of("ORG_OWNER", "ORG_MANAGER");
        List<String> adminRoles  = List.of("CONFIG_ADMIN", "PLATFORM_ADMIN");
        List<String> cancelRoles = List.of("ORG_OWNER", "CONFIG_ADMIN", "PLATFORM_ADMIN");

        List<WorkflowTransition> transitions = new ArrayList<>();
        transitions.add(tx(wf, "PLANNING",    "SUBMITTED",   "submit",   "Submit",          orgRoles));
        transitions.add(tx(wf, "PLANNING",    "CANCELLED",   "cancel",   "Cancel",          cancelRoles));
        transitions.add(tx(wf, "SUBMITTED",   "CONFIRMED",   "confirm",  "Confirm",         adminRoles));
        transitions.add(tx(wf, "SUBMITTED",   "CANCELLED",   "cancel",   "Cancel",          cancelRoles));
        transitions.add(tx(wf, "CONFIRMED",   "IN_PROGRESS", "start",    "Start Event",     orgRoles));
        transitions.add(tx(wf, "CONFIRMED",   "CANCELLED",   "cancel",   "Cancel",          cancelRoles));
        transitions.add(tx(wf, "IN_PROGRESS", "COMPLETED",   "complete", "Mark Complete",   orgRoles));
        wf.setTransitions(transitions);

        wfRepo.save(wf);
        log.info("Seeded CORPORATE_EVENT workflow");
    }

    // ── builder helpers ───────────────────────────────────────────────────────

    private WorkflowState state(WorkflowDefinition wf, String name, String label,
                                 String color, boolean terminal, int order) {
        WorkflowState s = new WorkflowState();
        s.setWorkflow(wf);
        s.setName(name);
        s.setLabel(label);
        s.setColor(color);
        s.setTerminal(terminal);
        s.setDisplayOrder(order);
        return s;
    }

    private WorkflowTransition tx(WorkflowDefinition wf, String from, String to,
                                   String trigger, String label, List<String> roles) {
        WorkflowTransition t = new WorkflowTransition();
        t.setWorkflow(wf);
        t.setFromState(from);
        t.setToState(to);
        t.setTriggerName(trigger);
        t.setTriggerLabel(label);
        t.setAllowedRoles(roles);
        t.setRequiresApproval(false);
        return t;
    }

    private String titleCase(String s) {
        return s.replace("_", " ").charAt(0) + s.substring(1).toLowerCase().replace("_", " ");
    }
}
