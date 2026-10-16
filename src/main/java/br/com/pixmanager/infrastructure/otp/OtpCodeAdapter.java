package br.com.pixmanager.infrastructure.otp;

import br.com.pixmanager.core.port.out.OtpCodePortOut;
import br.com.pixmanager.infrastructure.security.crypto.CryptoService;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.UUID;

/** Código de 6 dígitos de {@link SecureRandom}; hash = HMAC-SHA256 (chave HMAC do {@link CryptoService}) de {@code otp:<chave>:<código>}. */
@Component
public class OtpCodeAdapter implements OtpCodePortOut {

    private final SecureRandom random = new SecureRandom();
    private final CryptoService crypto;

    public OtpCodeAdapter(CryptoService crypto) {
        this.crypto = crypto;
    }

    @Override
    public String generate() {
        return "%06d".formatted(random.nextInt(1_000_000));
    }

    @Override
    public String hash(UUID pixKeyId, String code) {
        return crypto.blindIndex("otp:" + pixKeyId + ":" + code);
    }
}
