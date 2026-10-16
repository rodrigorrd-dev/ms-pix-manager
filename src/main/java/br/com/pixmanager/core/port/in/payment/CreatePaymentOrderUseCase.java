package br.com.pixmanager.core.port.in.payment;

import br.com.pixmanager.core.domain.entity.Caller;
import br.com.pixmanager.core.domain.entity.Money;
import br.com.pixmanager.core.domain.entity.PaymentOrder;
import br.com.pixmanager.core.domain.enums.PaymentMethod;
import br.com.pixmanager.core.domain.enums.PaymentOrigin;
import br.com.pixmanager.core.domain.enums.PixKeyType;

import java.util.UUID;

public interface CreatePaymentOrderUseCase {

    /** Atalho: {@link #prepare} + {@link #submit}. Quem controla a transação por fora (idempotência) chama as duas fases. */
    default PaymentOrderResult create(Caller caller, CreatePaymentOrderCommand command) {
        return submit(prepare(caller, command));
    }

    /** Fase 1 — validações e resolução do favorecido no DICT (HTTP). NUNCA chamar dentro de transação de banco. */
    PreparedPaymentOrder prepare(Caller caller, CreatePaymentOrderCommand command);

    /** Fase 2 — uma transação: limite + reserva PENDING + ordem + evento. Sem I/O externo. */
    PaymentOrderResult submit(PreparedPaymentOrder prepared);

    /** Ordem montada (ainda não persistida) pronta para a fase 2. */
    record PreparedPaymentOrder(PaymentOrder order) {}

    /**
     * {@code systemInitiated}: execução de agendamento/Pix Automático (sem dispositivo do cliente ⇒ sem limite de dispositivo).
     * {@code deviceId}: id do dispositivo do cliente; ausente ⇒ tratado como NÃO cadastrado (padrão seguro, mais restritivo).
     */
    record CreatePaymentOrderCommand(UUID payerAccountId, PaymentMethod method, PaymentOrigin origin, Money amount,
                                     PixKeyType pixKeyType, String pixKey, String documentHint, String ispb, String branch,
                                     String account, String accountType, String name, String document, String qrPayload,
                                     String payerMessage, String deviceId, boolean systemInitiated) {}

    sealed interface PaymentOrderResult {

        /** Ordem validada, limite consumido e reserva PENDING no core (o sweeper reserva e o fluxo segue). */
        record Created(PaymentOrder order) implements PaymentOrderResult {}

        /** Recusada por regra de negócio (limite, etc.): a ordem fica REJECTED para auditoria e a API responde 422 com o código. */
        record Rejected(PaymentOrder order, String errorCode, String detail) implements PaymentOrderResult {}
    }
}
