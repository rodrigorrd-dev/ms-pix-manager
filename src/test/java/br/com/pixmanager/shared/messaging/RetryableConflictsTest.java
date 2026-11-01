package br.com.pixmanager.shared.messaging;

import br.com.pixmanager.shared.constant.ErrorCodes;
import br.com.pixmanager.shared.exception.BusinessException;
import br.com.pixmanager.shared.exception.ConflictException;
import br.com.pixmanager.shared.exception.IntegrationException;
import br.com.pixmanager.shared.exception.NotFoundException;
import br.com.pixmanager.shared.exception.PreconditionFailedException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetryableConflictsTest {

    @Test
    void optimisticConflictBecomesRetryableIntegrationFailure() {
        assertThatThrownBy(() -> RetryableConflicts.retryOnConflict(() -> { throw new PreconditionFailedException("versão"); }))
                .isInstanceOf(IntegrationException.class).hasCauseInstanceOf(PreconditionFailedException.class);
    }

    @Test
    void uniquenessRaceBecomesRetryableIntegrationFailure() {
        assertThatThrownBy(() -> RetryableConflicts.retryOnConflict(() -> { throw new ConflictException(ErrorCodes.CONFLICT, "dup"); }))
                .isInstanceOf(IntegrationException.class);
    }

    @Test
    void otherBusinessFailuresStayDefinitive() {
        assertThatThrownBy(() -> RetryableConflicts.retryOnConflict(() -> { throw new NotFoundException(ErrorCodes.NOT_FOUND, "x"); }))
                .isInstanceOf(BusinessException.class).isNotInstanceOf(IntegrationException.class);
    }

    @Test
    void returnsTheValueOnSuccess() {
        assertThat(RetryableConflicts.retryOnConflict(() -> 42)).isEqualTo(42);
    }
}
