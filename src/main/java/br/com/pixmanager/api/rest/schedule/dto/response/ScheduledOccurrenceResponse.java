package br.com.pixmanager.api.rest.schedule.dto.response;

import br.com.pixmanager.core.domain.enums.OccurrenceStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record ScheduledOccurrenceResponse(UUID id, int sequenceNo, LocalDate dueDate, OccurrenceStatus status, int attemptCount,
                                          Instant nextAttemptAt, Instant customerNotifiedAt, UUID paymentOrderId, String lastErrorCode) {}
