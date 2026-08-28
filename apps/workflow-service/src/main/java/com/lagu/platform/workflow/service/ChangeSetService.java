package com.lagu.platform.workflow.service;

import com.lagu.platform.common.exception.PlatformException;
import com.lagu.platform.common.exception.ResourceNotFoundException;
import com.lagu.platform.workflow.client.RecordServiceClient;
import com.lagu.platform.workflow.domain.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChangeSetService {

    private final ChangeSetRepository changeSetRepo;
    private final WorkflowStateRepository stateRepo;
    private final WorkflowDefinitionRepository definitionRepo;
    private final RecordServiceClient recordServiceClient;

    /**
     * The states of an object type's workflow that hold edits for review, plus the workflow those
     * states belong to so a caller can attribute a change set without a second lookup.
     *
     * <p>An empty {@code states} set means every edit applies straight through — which is the
     * common case, and is also the answer when the object type has no workflow at all.
     *
     * @param workflowId null when the object type has no active workflow
     */
    public record GatedStates(UUID workflowId, Set<String> states) {
        public static GatedStates none() {
            return new GatedStates(null, Set.of());
        }

        public boolean holds(String stateName) {
            return stateName != null && states.contains(stateName);
        }
    }

    /**
     * Resolves the gated states for an object type, preferring an org's own workflow over the
     * platform-level one — the same precedence {@code findForObjectType} applies everywhere else.
     */
    // readOnly transaction, not because anything is written, but because states is a LAZY
    // @OneToMany: without a session open the stream below throws LazyInitializationException and
    // the caller sees a 500. record-service fails closed on that, so the bug presented as "no
    // vendor can edit anything" rather than as a stack trace anyone would notice.
    @Transactional(readOnly = true)
    public GatedStates gatedStatesFor(String objectType, UUID tenantId) {
        // Uppercased on the way in because create() stores it that way. A caller passing the
        // raw value would otherwise match nothing and be told, wrongly and silently, that
        // no state is gated — the failure mode this whole change is about.
        List<WorkflowDefinition> defs = definitionRepo.findForObjectType(
                objectType == null ? null : objectType.toUpperCase(), tenantId);
        if (defs.isEmpty()) return GatedStates.none();

        WorkflowDefinition wf = defs.get(0);
        Set<String> gated = wf.getStates().stream()
                .filter(WorkflowState::isRequiresChangeApproval)
                .map(WorkflowState::getName)
                .collect(java.util.stream.Collectors.toSet());
        return new GatedStates(wf.getId(), gated);
    }

    /**
     * Returns true if the record's current workflow state requires change-set review.
     *
     * @deprecated record-service gates on {@link #gatedStatesFor} instead — one cached answer per
     *     object type rather than a network round trip per record. Kept because it is the clearer
     *     expression of the rule for a single state, and is what the tests read.
     */
    @Deprecated
    public boolean requiresApproval(UUID workflowId, String stateName) {
        return stateRepo
                .findByWorkflowIdAndName(workflowId, stateName)
                .map(WorkflowState::isRequiresChangeApproval)
                .orElse(false);
    }

    @Transactional
    public ChangeSet submit(UUID recordId, UUID tenantId, String objectType,
                            UUID workflowId, Map<String, Object> originalData,
                            Map<String, Object> proposedData, UUID submittedBy) {
        ChangeSet cs = new ChangeSet();
        cs.setRecordId(recordId);
        cs.setTenantId(tenantId);
        cs.setObjectType(objectType);
        // definitionRepo, not stateRepo: this is a workflow id, and looking it up in the state
        // table found nothing, so every change set was saved with a null workflow link. Harmless
        // while nothing passed a real workflowId — record-service now does.
        if (workflowId != null) {
            definitionRepo.findById(workflowId).ifPresent(cs::setWorkflow);
        }
        cs.setOriginalData(originalData);
        cs.setProposedData(proposedData);
        cs.setSubmittedBy(submittedBy);
        return changeSetRepo.save(cs);
    }

    /**
     * Records the reviewer's decision and, when approving, applies the change to the record.
     *
     * The apply step used to be missing entirely: this method set the status and saved, so every
     * approved change set was recorded as APPROVED while the record kept its old values. The
     * vendor saw their edit approved and nothing changed, and {@code correctedData} — the
     * reviewer's hand-corrected version — was written to a column nothing ever read.
     *
     * Ordering matters. The record is updated *before* the decision is committed, so a failed
     * apply rolls the whole review back and leaves the change set PENDING to be retried. The
     * reverse order is what produced the silent data loss, and re-approving is idempotent
     * because the payload is absolute rather than a delta.
     */
    @Transactional
    public ChangeSet review(UUID changeSetId, String decision, String adminComment,
                            Map<String, Object> correctedData, UUID reviewedBy) {
        ChangeSet cs = changeSetRepo.findById(changeSetId)
                .orElseThrow(() -> new IllegalArgumentException("ChangeSet not found: " + changeSetId));

        // Reviewing an already-decided change set is an ordinary race — a double-click, or two
        // admins working the same queue — so it answers 409 with what actually happened rather
        // than an unmapped IllegalStateException surfacing as 500 "An unexpected error occurred".
        if (!"PENDING".equals(cs.getStatus())) {
            throw new PlatformException("CHANGE_SET_NOT_PENDING",
                    "This change request was already " + cs.getStatus().toLowerCase()
                            + " and cannot be reviewed again.",
                    HttpStatus.CONFLICT);
        }

        boolean approved = "APPROVED".equals(decision);

        if (approved) {
            // The reviewer's corrections win over what the vendor proposed; that is the entire
            // point of the "edit before approving" path.
            Map<String, Object> dataToApply =
                    (correctedData != null && !correctedData.isEmpty()) ? correctedData : cs.getProposedData();

            if (dataToApply == null || dataToApply.isEmpty()) {
                throw new PlatformException("CHANGE_SET_EMPTY",
                        "This change request has no data to apply, so it cannot be approved. "
                                + "Approving it would record a change that never happened.",
                        HttpStatus.UNPROCESSABLE_ENTITY);
            }

            recordServiceClient.applyApprovedData(
                    cs.getRecordId(), cs.getTenantId(), reviewedBy, dataToApply);
            log.info("Applied approved change set {} to record {} ({} fields)",
                    changeSetId, cs.getRecordId(), dataToApply.size());
        }

        cs.setStatus(approved ? "APPROVED" : "REJECTED");
        cs.setAdminComment(adminComment);
        cs.setCorrectedData(correctedData);
        cs.setReviewedBy(reviewedBy);
        cs.setReviewedAt(Instant.now());
        return changeSetRepo.save(cs);
    }

    /**
     * The same three failure modes review() above already maps, left unmapped here when that was
     * fixed: a vendor withdrawing an edit that an admin has just actioned, or that was never
     * theirs, got 500 "An unexpected error occurred" and no way to tell the two apart.
     */
    @Transactional
    public ChangeSet withdraw(UUID changeSetId, UUID requestedBy) {
        ChangeSet cs = changeSetRepo.findById(changeSetId)
                .orElseThrow(() -> new ResourceNotFoundException("ChangeSet", changeSetId.toString()));
        if (!"PENDING".equals(cs.getStatus())) {
            throw new PlatformException("CHANGE_SET_NOT_PENDING",
                    "This change request was already " + cs.getStatus().toLowerCase()
                            + " and cannot be withdrawn.",
                    HttpStatus.CONFLICT);
        }
        if (!cs.getSubmittedBy().equals(requestedBy)) {
            // Authorization, not state — 403, so a client does not retry it as a conflict.
            throw new PlatformException("NOT_CHANGE_SET_SUBMITTER",
                    "Only the person who submitted a change request can withdraw it.",
                    HttpStatus.FORBIDDEN);
        }
        cs.setStatus("WITHDRAWN");
        return changeSetRepo.save(cs);
    }

    public List<ChangeSet> listByRecord(UUID recordId) {
        return changeSetRepo.findByRecordIdOrderBySubmittedAtDesc(recordId);
    }

    public List<ChangeSet> listPending() {
        return changeSetRepo.findByStatusOrderBySubmittedAtAsc("PENDING");
    }

    public List<ChangeSet> listByOrgAndStatus(UUID tenantId, String status) {
        return changeSetRepo.findByTenantIdAndStatusOrderBySubmittedAtDesc(tenantId, status);
    }
}
