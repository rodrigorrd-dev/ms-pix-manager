package br.com.pixmanager.core.port.in.charge;

import br.com.pixmanager.core.domain.entity.AdditionalInfo;
import br.com.pixmanager.core.domain.enums.ChargeType;
import br.com.pixmanager.core.domain.entity.Debtor;
import br.com.pixmanager.core.domain.entity.Money;
import br.com.pixmanager.core.domain.entity.Caller;

import java.time.LocalDate;
import java.util.List;

public interface CreateChargeUseCase {

    CreateChargeResult create(Caller caller, CreateChargeCommand command);

    /** {@code txid == null} ⇒ gerado pelo PSP ({@code POST /cob}). */
    record CreateChargeCommand(ChargeType type, String txid, String pixKey, Money amount, Integer expirationSeconds,
                               LocalDate dueDate, Integer validityAfterDueDays, Debtor debtor, String payerRequest,
                               List<AdditionalInfo> additionalInfo) {}

    /** Falhas esperadas como VALOR (code-design): conflito de txid não é exceção do domínio. */
    sealed interface CreateChargeResult {

        record Created(ChargeDetails details) implements CreateChargeResult {}

        /** Mesmo txid e mesmo pedido: devolve o recurso existente (PUT idempotente). */
        record AlreadyExists(ChargeDetails details) implements CreateChargeResult {}

        /** Mesmo txid, pedido diferente (BR-COB-01). */
        record TxIdConflict() implements CreateChargeResult {}
    }
}
