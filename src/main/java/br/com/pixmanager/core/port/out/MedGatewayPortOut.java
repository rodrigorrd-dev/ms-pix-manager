package br.com.pixmanager.core.port.out;

import br.com.pixmanager.core.domain.enums.MedCaseType;

/**
 * Comunicação do caso MED com o DICT (via dict-gateway; contrato RASCUNHO, docs/05 §6b): abre a notificação de infração do nosso cliente
 * pagador (REPORTER) e a cancela. Falha técnica ⇒ {@code DependencyUnavailableException}; recusa de negócio ⇒ {@code BusinessException}.
 */
public interface MedGatewayPortOut {

    /** @return o id do pedido de recuperação de valores no DICT (funds-recoveries/{id}). */
    String report(String endToEndId, MedCaseType type, String description, int layer);

    void cancel(String fundsRecoveryId);
}
