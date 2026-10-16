package br.com.pixmanager.core.port.in.refund;

import java.time.Instant;

/** Resultado do pacs.004 vindo do spi-gateway ({@code pix.spi.refund-status.v1}). Idempotente. */
public interface SettleRefundUseCase {

    void apply(String consumerGroup, String messageId, String topic, int partition, long offset, String rtrId, boolean settled,
               String reason, Instant at);
}
