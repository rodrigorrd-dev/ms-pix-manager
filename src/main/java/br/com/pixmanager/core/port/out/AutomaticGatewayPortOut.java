package br.com.pixmanager.core.port.out;

import br.com.pixmanager.core.domain.entity.Money;

/**
 * Resposta do PSP do pagador ao recebedor (via spi-gateway; contrato RASCUNHO, docs/05 §6b). Falha técnica ⇒ DependencyUnavailableException;
 * recusa de negócio ⇒ BusinessException.
 */
public interface AutomaticGatewayPortOut {

    void approve(String idRec, Money payerMaxAmount);

    void reject(String idRec, String rejectionCode);

    void cancel(String idRec, String cancelledBy);
}
