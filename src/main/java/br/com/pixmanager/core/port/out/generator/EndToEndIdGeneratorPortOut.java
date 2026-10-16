package br.com.pixmanager.core.port.out.generator;

/** Gera ids do SPI. O ISPB vem de configuração (nunca literal). */
public interface EndToEndIdGeneratorPortOut {

    /** {@code E} + ISPB(8) + {@code yyyyMMddHHmm} UTC + 11 alfanuméricos = 32 chars (pagamento). */
    String nextEndToEndId();

    /** {@code D} + ISPB(8) + {@code yyyyMMddHHmm} UTC + 11 alfanuméricos = 32 chars ({@code rtrId} da devolução). */
    String nextReturnId();
}
