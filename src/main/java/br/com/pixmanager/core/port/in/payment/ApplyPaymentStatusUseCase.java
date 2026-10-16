package br.com.pixmanager.core.port.in.payment;

import java.time.Instant;

/** Resultado do pacs.002 vindo do spi-gateway ({@code pix.spi.payment-status.v1}). Idempotente (inbox + estado final). */
public interface ApplyPaymentStatusUseCase {

    void apply(String consumerGroup, String messageId, String topic, int partition, long offset, String endToEndId,
               SpiStatus status, String reasonCode, Instant at);

    enum SpiStatus { SENT, SETTLED, REJECTED }
}
