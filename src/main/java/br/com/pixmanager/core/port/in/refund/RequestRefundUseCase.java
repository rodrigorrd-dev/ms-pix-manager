package br.com.pixmanager.core.port.in.refund;

import br.com.pixmanager.core.domain.entity.Caller;
import br.com.pixmanager.core.domain.entity.Money;
import br.com.pixmanager.core.domain.entity.Refund;
import br.com.pixmanager.core.domain.enums.RefundNature;

public interface RequestRefundUseCase {

    RequestRefundResult request(Caller caller, RequestRefundCommand command);

    record RequestRefundCommand(String endToEndId, String clientRefundId, Money amount, RefundNature nature, String description) {}

    sealed interface RequestRefundResult {

        /** Primeira vez: devolução criada, valor devolvível reservado, reserva PENDING no core, evento na outbox. */
        record Created(Refund refund) implements RequestRefundResult {}

        /** Repetição idêntica (mesmo e2e + id + corpo): devolve o MESMO recurso, sem nova mensagem ao SPI (BR-REF-01). */
        record Replayed(Refund refund) implements RequestRefundResult {}
    }
}
