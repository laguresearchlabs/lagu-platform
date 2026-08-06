package com.lagu.platform.workflow.service;

import com.lagu.platform.common.exception.PlatformException;
import com.lagu.platform.workflow.client.RecordServiceClient;
import com.lagu.platform.workflow.domain.ChangeSet;
import com.lagu.platform.workflow.domain.ChangeSetRepository;
import com.lagu.platform.workflow.domain.WorkflowStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Reviewing a change set has to actually change the record. It previously only wrote the
 * decision row, so an approved vendor edit was recorded as APPROVED and then silently dropped;
 * these pin down that the apply happens, that the reviewer's corrections take precedence, and —
 * most importantly — that a failed apply never leaves the change set marked APPROVED.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChangeSetServiceTest {

    @Mock ChangeSetRepository      changeSetRepo;
    @Mock WorkflowStateRepository  stateRepo;
    @Mock RecordServiceClient      recordServiceClient;

    @InjectMocks ChangeSetService service;

    static final UUID CHANGE_SET = UUID.randomUUID();
    static final UUID RECORD     = UUID.randomUUID();
    static final UUID ORG        = UUID.randomUUID();
    static final UUID REVIEWER   = UUID.randomUUID();
    static final UUID SUBMITTER  = UUID.randomUUID();

    ChangeSet pending;

    @BeforeEach
    void setUp() {
        pending = new ChangeSet();
        pending.setId(CHANGE_SET);
        pending.setRecordId(RECORD);
        pending.setTenantId(ORG);
        pending.setObjectType("VENUE");
        pending.setStatus("PENDING");
        pending.setSubmittedBy(SUBMITTER);
        pending.setOriginalData(Map.of("name", "Grand Palace Lawn", "capacity", 900));
        pending.setProposedData(Map.of("name", "Grand Palace Lawn", "capacity", 950));

        when(changeSetRepo.findById(CHANGE_SET)).thenReturn(Optional.of(pending));
        when(changeSetRepo.save(any(ChangeSet.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void approvingAppliesTheProposedDataToTheRecord() {
        ChangeSet result = service.review(CHANGE_SET, "APPROVED", "Verified", null, REVIEWER);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> data = ArgumentCaptor.forClass(Map.class);
        verify(recordServiceClient).applyApprovedData(eq(RECORD), eq(ORG), eq(REVIEWER), data.capture());

        assertThat(data.getValue()).containsEntry("capacity", 950);
        assertThat(result.getStatus()).isEqualTo("APPROVED");
        assertThat(result.getReviewedBy()).isEqualTo(REVIEWER);
        assertThat(result.getReviewedAt()).isNotNull();
    }

    @Test
    void correctedDataOverridesWhatTheVendorProposed() {
        Map<String, Object> corrected = Map.of("name", "Grand Palace Lawn", "capacity", 925);

        service.review(CHANGE_SET, "APPROVED", "Capped to certified capacity", corrected, REVIEWER);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> data = ArgumentCaptor.forClass(Map.class);
        verify(recordServiceClient).applyApprovedData(any(), any(), any(), data.capture());
        assertThat(data.getValue()).containsEntry("capacity", 925);
    }

    @Test
    void emptyCorrectedDataFallsBackToTheProposedData() {
        service.review(CHANGE_SET, "APPROVED", "ok", Map.of(), REVIEWER);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> data = ArgumentCaptor.forClass(Map.class);
        verify(recordServiceClient).applyApprovedData(any(), any(), any(), data.capture());
        assertThat(data.getValue()).containsEntry("capacity", 950);
    }

    @Test
    void rejectingLeavesTheRecordUntouched() {
        ChangeSet result = service.review(CHANGE_SET, "REJECTED", "Capacity exceeds fire cert", null, REVIEWER);

        verifyNoInteractions(recordServiceClient);
        assertThat(result.getStatus()).isEqualTo("REJECTED");
        assertThat(result.getAdminComment()).isEqualTo("Capacity exceeds fire cert");
    }

    /** The whole point of the fix: never report success for a change that did not land. */
    @Test
    void aFailedApplyPropagatesAndDoesNotMarkTheChangeSetApproved() {
        doThrow(new PlatformException("CHANGE_SET_APPLY_FAILED",
                "record-service reported: name: field is required",
                HttpStatus.UNPROCESSABLE_ENTITY))
                .when(recordServiceClient).applyApprovedData(any(), any(), any(), any());

        assertThatThrownBy(() -> service.review(CHANGE_SET, "APPROVED", "ok", null, REVIEWER))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("name: field is required");

        // The transaction rolls back, but the in-memory entity must not have been saved as
        // approved either — otherwise a non-transactional caller would persist the lie.
        verify(changeSetRepo, never()).save(any(ChangeSet.class));
    }

    @Test
    void anEmptyChangeIsRefusedRatherThanRecordedAsApplied() {
        pending.setProposedData(Map.of());

        assertThatThrownBy(() -> service.review(CHANGE_SET, "APPROVED", "ok", null, REVIEWER))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("no data to apply");

        verifyNoInteractions(recordServiceClient);
        verify(changeSetRepo, never()).save(any(ChangeSet.class));
    }

    /** A double-click or a second admin working the same queue is a 409, not a 500. */
    @Test
    void reviewingAnAlreadyDecidedChangeSetConflicts() {
        pending.setStatus("APPROVED");

        assertThatThrownBy(() -> service.review(CHANGE_SET, "APPROVED", "ok", null, REVIEWER))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("already approved");

        verifyNoInteractions(recordServiceClient);
    }
}
