package br.com.pixmanager.infrastructure.otp;

import br.com.pixmanager.core.domain.enums.OtpChannel;
import br.com.pixmanager.core.port.out.OtpSenderPortOut;
import br.com.pixmanager.shared.exception.DependencyUnavailableException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Padrão SEGURO: sem canal configurado ({@code pix.otp.sender=none}) o app NÃO envia nem loga código; a prova de posse responde 503. */
@Component
@ConditionalOnProperty(prefix = "pix.otp", name = "sender", havingValue = "none", matchIfMissing = true)
public class NoOpOtpSenderAdapter implements OtpSenderPortOut {

    @Override
    public void send(OtpChannel channel, String destination, String code, Duration validFor) {
        throw new DependencyUnavailableException("Canal de envio de OTP não configurado (pix.otp.sender)", null);
    }
}
