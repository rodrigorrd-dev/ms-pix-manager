package br.com.pixmanager.api.rest.payment.dto.response;

/** BR Code decodificado. {@code pixKeyMasked}: a chave do QR pode ser CPF/telefone/e-mail (PII) — nunca em claro. */
public record BrCodeResponse(String pixKeyMasked, String paymentUrl, String amount, String txid, String merchantName, String merchantCity,
                             boolean dynamic) {}
