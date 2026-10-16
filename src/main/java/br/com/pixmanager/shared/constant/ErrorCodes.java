package br.com.pixmanager.shared.constant;

/**
 * Catálogo ÚNICO de códigos de erro (docs/05 §4.5). Literal {@code "PIX-…"} fora desta classe falha no
 * {@code ErrorCodeCatalogTest}.
 */
public final class ErrorCodes {

    private ErrorCodes() {}

    public static final String INVALID_PAYLOAD          = "PIX-400-001";
    public static final String INVALID_PRECONDITION     = "PIX-400-002";
    public static final String UNAUTHENTICATED          = "PIX-401-001";
    public static final String ACCESS_DENIED            = "PIX-403-001";
    public static final String NOT_FOUND                = "PIX-404-001";
    public static final String METHOD_NOT_ALLOWED       = "PIX-405-001";
    public static final String NOT_ACCEPTABLE           = "PIX-406-001";
    public static final String TXID_CONFLICT            = "PIX-409-020";
    public static final String CONFLICT                 = "PIX-409-021";
    public static final String IDEMPOTENCY_IN_PROGRESS  = "PIX-409-900";
    public static final String OUTDATED_VERSION         = "PIX-412-001";
    public static final String UNSUPPORTED_MEDIA_TYPE   = "PIX-415-001";
    public static final String INVALID_CPF              = "PIX-422-001";
    public static final String INVALID_CNPJ             = "PIX-422-002";
    public static final String KEY_NOT_OWNED            = "PIX-422-010";
    public static final String KEY_LIMIT_EXCEEDED       = "PIX-422-011";
    public static final String INVALID_PIX_KEY          = "PIX-422-012";
    public static final String INVALID_BR_CODE          = "PIX-422-013";   // BR Code ilegível, CRC inválido ou não é Pix
    public static final String INVALID_VALUE_FORMAT     = "PIX-422-003";   // valor monetário / endToEndId fora do formato
    public static final String WEBHOOK_URL_REJECTED     = "PIX-422-017";   // URL do webhook fora da política (HTTPS, host público)
    public static final String QR_DYNAMIC_INVALID       = "PIX-422-018";   // QR dinâmico: payload/assinatura inválidos, cobrança inativa ou vencida
    public static final String OTP_INVALID              = "PIX-422-014";   // código de prova de posse errado
    public static final String OTP_EXPIRED              = "PIX-422-015";   // código vencido (peça outro)
    public static final String OTP_ATTEMPTS_EXCEEDED    = "PIX-422-016";   // tentativas esgotadas (peça outro)
    public static final String OTP_RATE_LIMITED         = "PIX-429-002";   // pedidos de código em excesso
    public static final String CHARGE_NOT_ACTIVE        = "PIX-422-020";
    public static final String KEY_NOT_ACTIVE           = "PIX-422-021";
    public static final String REFUND_EXCEEDS_AMOUNT    = "PIX-422-030";
    public static final String REFUND_OUT_OF_WINDOW     = "PIX-422-031";
    public static final String DESTINATION_MISMATCH     = "PIX-422-040";
    public static final String LIMIT_EXCEEDED           = "PIX-422-050";
    public static final String INSUFFICIENT_FUNDS       = "PIX-422-051";
    public static final String LIMIT_ABOVE_CEILING      = "PIX-422-052";
    public static final String AUTOMATIC_NOT_AUTHORIZED = "PIX-422-060";
    public static final String INVALID_SCHEDULE         = "PIX-422-061";
    public static final String SCHEDULE_NOT_CANCELLABLE = "PIX-422-062";
    public static final String MED_CONTESTATION_EXPIRED = "PIX-422-070";
    public static final String MED_WINDOW_EXPIRED       = "PIX-422-071";
    public static final String BUSINESS_RULE            = "PIX-422-000";
    public static final String IDEMPOTENCY_MISMATCH     = "PIX-422-900";
    public static final String PRECONDITION_REQUIRED    = "PIX-428-001";
    public static final String TOO_MANY_REQUESTS        = "PIX-429-001";
    public static final String INTERNAL_ERROR           = "PIX-500-001";
    public static final String DEPENDENCY_FAILURE       = "PIX-502-001";
    public static final String DEPENDENCY_UNAVAILABLE   = "PIX-503-001";
}
