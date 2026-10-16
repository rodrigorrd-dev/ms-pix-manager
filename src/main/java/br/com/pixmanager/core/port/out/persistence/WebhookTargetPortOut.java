package br.com.pixmanager.core.port.out.persistence;

import java.util.Optional;
import java.util.UUID;

/** Descobre QUAL chave recebeu o Pix (a assinatura é por chave). */
public interface WebhookTargetPortOut {

    /**
     * 1º o índice cego da chave informado pelo SPI; senão a chave da cobrança que o Pix quitou. Pix sem chave identificável (ex.: pagamento
     * por dados bancários) não notifica ninguém.
     */
    Optional<UUID> keyIdFor(String receiverKeyHash, UUID chargeId);
}
