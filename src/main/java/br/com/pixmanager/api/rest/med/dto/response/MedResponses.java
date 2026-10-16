package br.com.pixmanager.api.rest.med.dto.response;

import br.com.pixmanager.core.domain.enums.MedCaseType;
import br.com.pixmanager.core.domain.enums.MedRole;
import br.com.pixmanager.core.domain.enums.MedStatus;

import java.time.Instant;
import java.util.UUID;

public final class MedResponses {

    private MedResponses() {}

    public record Case(UUID id, String endToEndId, UUID accountId, MedCaseType caseType, MedRole role, MedStatus status, int layer, String description,
                       Instant reportedAt, Instant analysisDeadlineAt, String trackedAmount, String blockedAmount, UUID refundId, String dictFundsRecoveryId,
                       long version) {}

    public record History(MedStatus from, MedStatus to, String actor, String note, Instant occurredAt) {}

    public record Contestation(UUID id, UUID caseId, UUID refundId, String status, String reason, Instant refundedAt, Instant deadlineAt, Instant decidedAt) {}
}
