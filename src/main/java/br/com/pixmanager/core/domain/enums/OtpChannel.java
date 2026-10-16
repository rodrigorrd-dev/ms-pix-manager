package br.com.pixmanager.core.domain.enums;

/** Canal de entrega do código de prova de posse. PHONE ⇒ SMS; EMAIL ⇒ e-mail. */
public enum OtpChannel {
    SMS, EMAIL;

    public static OtpChannel forKeyType(PixKeyType type) {
        return switch (type) {
            case PHONE -> SMS;
            case EMAIL -> EMAIL;
            default -> throw new IllegalArgumentException("Prova de posse por OTP só existe para PHONE e EMAIL: " + type);
        };
    }
}
