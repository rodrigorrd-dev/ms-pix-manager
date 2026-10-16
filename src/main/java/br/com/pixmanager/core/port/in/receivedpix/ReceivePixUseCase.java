package br.com.pixmanager.core.port.in.receivedpix;

import br.com.pixmanager.core.domain.entity.ReceivedPix;
import br.com.pixmanager.core.domain.entity.ValueComponent;
import br.com.pixmanager.core.domain.entity.Money;

import java.time.Instant;
import java.util.List;

public interface ReceivePixUseCase {

    ReceivePixResult receive(ReceivePixCommand command);

    /** Mensagem do SPI (via spi-gateway) já traduzida para o domínio. */
    record ReceivePixCommand(String consumerGroup, String messageId, String topic, int partition, long offset,
                             String endToEndId, String txid, Money amount, Instant settledAt, String receiverIspb,
                             String receiverBranch, String receiverAccount, String receiverAccountType, String payerDocument,
                             String payerName, String payerIspb, String payerBranch, String payerAccount,
                             String payerAccountType, String payerInfo, List<ValueComponent> components, String receiverKeyHash) {

        /** Sem o índice cego da chave (Pix por dados bancários, ou SPI que não informa): o webhook, se houver, é achado pela cobrança. */
        public ReceivePixCommand(String consumerGroup, String messageId, String topic, int partition, long offset, String endToEndId, String txid,
                                 Money amount, Instant settledAt, String receiverIspb, String receiverBranch, String receiverAccount,
                                 String receiverAccountType, String payerDocument, String payerName, String payerIspb, String payerBranch,
                                 String payerAccount, String payerAccountType, String payerInfo, List<ValueComponent> components) {
            this(consumerGroup, messageId, topic, partition, offset, endToEndId, txid, amount, settledAt, receiverIspb, receiverBranch,
                    receiverAccount, receiverAccountType, payerDocument, payerName, payerIspb, payerBranch, payerAccount, payerAccountType,
                    payerInfo, components, null);
        }
    }

    sealed interface ReceivePixResult {

        /** Efeito aplicado (Pix gravado, cobrança conciliada, evento na outbox). */
        record Received(ReceivedPix pix) implements ReceivePixResult {}

        /** Mensagem repetida (inbox) ou {@code endToEndId} já gravado: nenhum efeito, offset confirmado. */
        record Duplicate() implements ReceivePixResult {}
    }
}
