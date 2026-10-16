package br.com.pixmanager.core.port.out;

import br.com.pixmanager.core.domain.entity.Money;

import java.time.Instant;

/**
 * Busca e VALIDA o payload JWS de um QR dinâmico (URL da tag 26/25 do BR Code). A implementação faz o HTTP (com proteção anti-SSRF),
 * confere a assinatura e a cadeia de certificados e devolve só o que o pagamento precisa. Rejeição do payload ⇒ BusinessException
 * ({@code PIX-422-018}); recebedor fora do ar/lento ⇒ DependencyUnavailableException.
 */
public interface DynamicQrPayloadPortOut {

    DynamicQrPayload fetch(String paymentUrl);

    /**
     * @param status    status da cobrança no PSP do recebedor (ATIVA, CONCLUIDA, REMOVIDA_...)
     * @param expiresAt fim da validade (cob: criação + expiração; cobv: vencimento + validade após o vencimento); null = sem prazo
     * @param amount    valor a pagar (cobv: {@code valor.final} se houver, senão {@code valor.original})
     */
    record DynamicQrPayload(String txid, String pixKey, Money amount, String status, Instant expiresAt) {}
}
