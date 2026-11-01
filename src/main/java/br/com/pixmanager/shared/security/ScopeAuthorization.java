package br.com.pixmanager.shared.security;

/** Escopos OAuth do BCB (API Pix 2.10.0) e de gestão. Constantes para {@code @PreAuthorize} — typo vira erro de build/teste. */
public final class ScopeAuthorization {

    private ScopeAuthorization() {}

    // API Pix (BCB)
    public static final String COB_READ = "hasAuthority('SCOPE_cob.read')";
    public static final String COB_WRITE = "hasAuthority('SCOPE_cob.write')";
    public static final String COBV_READ = "hasAuthority('SCOPE_cobv.read')";
    public static final String COBV_WRITE = "hasAuthority('SCOPE_cobv.write')";
    public static final String PIX_READ = "hasAuthority('SCOPE_pix.read')";
    public static final String PIX_WRITE = "hasAuthority('SCOPE_pix.write')";
    public static final String WEBHOOK_READ = "hasAuthority('SCOPE_webhook.read')";
    public static final String WEBHOOK_WRITE = "hasAuthority('SCOPE_webhook.write')";
    /** Back-office: reenviar entregas de webhook de qualquer cliente. Escopo próprio, separado do {@code webhook.write} do parceiro. */
    public static final String WEBHOOK_ADMIN = "hasAuthority('SCOPE_webhook.admin')";
    public static final String PAYLOADLOCATION_READ = "hasAuthority('SCOPE_payloadlocation.read')";
    public static final String PAYLOADLOCATION_WRITE = "hasAuthority('SCOPE_payloadlocation.write')";
    // gestão
    public static final String PIXKEY_READ = "hasAuthority('SCOPE_pixkey.read')";
    public static final String PIXKEY_WRITE = "hasAuthority('SCOPE_pixkey.write')";
    public static final String PAYMENT_READ = "hasAuthority('SCOPE_payment.read')";
    public static final String PAYMENT_WRITE = "hasAuthority('SCOPE_payment.write')";
    public static final String LIMIT_READ = "hasAuthority('SCOPE_limit.read')";
    public static final String LIMIT_WRITE = "hasAuthority('SCOPE_limit.write')";
    public static final String MED_READ = "hasAuthority('SCOPE_med.read')";
    public static final String SCHEDULE_READ = "hasAuthority('SCOPE_schedule.read')";
    public static final String SCHEDULE_WRITE = "hasAuthority('SCOPE_schedule.write')";
    public static final String AUTOMATIC_READ = "hasAuthority('SCOPE_automatic.read')";
    public static final String AUTOMATIC_WRITE = "hasAuthority('SCOPE_automatic.write')";
    public static final String MED_WRITE = "hasAuthority('SCOPE_med.write')";
    public static final String REPORT_READ = "hasAuthority('SCOPE_report.read')";

    /** Escopo que libera CPF/CNPJ completo na resposta (padrão é mascarado). */
    public static final String PII_DOCUMENT_READ_AUTHORITY = br.com.pixmanager.shared.constant.ScopeNames.PII_DOCUMENT_READ;
}
