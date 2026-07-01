package com.lagu.platform.workflow.service;

import com.lagu.platform.workflow.domain.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ChangeSetService {

    private final ChangeSetRepository changeSetRepo;
    private final WorkflowStateRepository stateRepo;

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
    public ChangeSet submit(UUID recordId, UUID orgId, String objectType,
                            UUID workflowId, Map<String, Object> originalData,
                            Map<String, Object> proposedData, UUID submittedBy) {
        ChangeSet cs = new ChangeSet();
        cs.setRecordId(recordId);
        cs.setOrgId(orgId);
        cs.setObjectType(objectType);
        if (workflowId != null) {
            stateRepo.findById(workflowId).ifPresent(s -> cs.setWorkflow(s.getWorkflow()));
        }
        cs.setOriginalData(originalData);
        cs.setProposedData(proposedData);
        cs.setSubmittedBy(submittedBy);
        return changeSetRepo.save(cs);
    }

    @Transactional
    public ChangeSet review(UUID changeSetId, String decision, String adminComment,
                            Map<String, Object> correctedData, UUID reviewedBy) {
        ChangeSet cs = changeSetRepo.findById(changeSetId)
                .orElseThrow(() -> new IllegalArgumentException("ChangeSet not found: " + changeSetId));

        if (!"PENDING".equals(cs.getStatus())) {
            throw new IllegalStateException("ChangeSet " + changeSetId + " is not PENDING");
        }

        cs.setStatus("APPROVED".equals(decision) ? "APPROVED" : "REJECTED");
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

    public List<ChangeSet> listByOrgAndStatus(UUID orgId, String status) {
        return changeSetRepo.findByOrgIdAndStatusOrderBySubmittedAtDesc(orgId, status);
    }
}
