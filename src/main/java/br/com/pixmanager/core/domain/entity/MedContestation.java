package br.com.pixmanager.core.domain.entity;

import java.time.Instant;
import java.util.UUID;

/** Contestação, pelo recebedor, de uma devolução feita via MED (BR-MED-07). {@code status}: OPEN | ACCEPTED | REJECTED | CANCELLED. */
public record MedContestation(UUID id, UUID caseId, UUID refundId, String status, String reason, Instant refundedAt, Instant deadlineAt,
                              Instant decidedAt, long version) {}
