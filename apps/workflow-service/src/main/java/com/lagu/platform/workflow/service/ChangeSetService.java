package com.lagu.platform.workflow.service;

import com.lagu.platform.common.exception.PlatformException;
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
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChangeSetService {

    private final ChangeSetRepository changeSetRepo;
    private final WorkflowStateRepository stateRepo;
    private final RecordServiceClient recordServiceClient;

    /**
     * Returns true if the record's current workflow state requires change-set review.
     * Callers (record-service via HTTP) check this before applying a PATCH directly.
     */
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
        if (workflowId != null) {
            stateRepo.findById(workflowId).ifPresent(s -> cs.setWorkflow(s.getWorkflow()));
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

    @Transactional
    public ChangeSet withdraw(UUID changeSetId, UUID requestedBy) {
        ChangeSet cs = changeSetRepo.findById(changeSetId)
                .orElseThrow(() -> new IllegalArgumentException("ChangeSet not found: " + changeSetId));
        if (!"PENDING".equals(cs.getStatus())) {
            throw new IllegalStateException("Only PENDING change sets can be withdrawn");
        }
        if (!cs.getSubmittedBy().equals(requestedBy)) {
            throw new IllegalStateException("Only the submitter can withdraw a change set");
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
