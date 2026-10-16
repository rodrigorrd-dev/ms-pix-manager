package br.com.pixmanager.infrastructure.otp;

import br.com.pixmanager.core.domain.enums.OtpChannel;
import br.com.pixmanager.core.port.out.OtpSenderPortOut;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** SÓ DESENVOLVIMENTO ({@code pix.otp.sender=log}): escreve o código no log para quem testa. NUNCA habilite em hml/prd (o código é segredo). */
@Component
@ConditionalOnProperty(prefix = "pix.otp", name = "sender", havingValue = "log")
public class LoggingOtpSenderAdapter implements OtpSenderPortOut {

    private static final Logger log = LoggerFactory.getLogger(LoggingOtpSenderAdapter.class);

    @Override
    public void send(OtpChannel channel, String destination, String code, Duration validFor) {
        log.warn("[DEV - NÃO USAR EM PRODUÇÃO] OTP {} para {}: {} (válido por {})", channel, destination, code, validFor);
    }
}
