package br.com.pixmanager.core.port.in.webhook;

import br.com.pixmanager.core.domain.entity.ReceivedPix;

public interface NotifyPixReceivedUseCase {

    /**
     * Enfileira a notificação do Pix na assinatura da chave recebedora (se houver). Roda NA MESMA transação que grava o Pix; uma falha aqui
     * nunca pode impedir o crédito (exceções não-SQL são registradas e engolidas).
     */
    void pixReceived(ReceivedPix pix, String receiverKeyHash);
}
