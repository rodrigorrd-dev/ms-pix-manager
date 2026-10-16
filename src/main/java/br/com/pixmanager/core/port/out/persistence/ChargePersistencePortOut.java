package br.com.pixmanager.core.port.out.persistence;

import br.com.pixmanager.core.domain.entity.Charge;
import br.com.pixmanager.core.domain.entity.TxId;

import java.util.List;
import java.util.Optional;

public interface ChargePersistencePortOut {

    Charge save(Charge charge);

    /** Unicidade do {@code txid} é POR DOCUMENTO do recebedor (API Pix 2.10.0). */
    Optional<Charge> findByReceiverHashAndTxid(String receiverDocumentHash, TxId txid);

    /** {@code txid} não é global: pode haver mais de uma cobrança (documentos diferentes). */
    List<Charge> findByTxid(TxId txid);
}
