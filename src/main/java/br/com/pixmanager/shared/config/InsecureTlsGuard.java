package br.com.pixmanager.shared.config;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import java.util.List;

/**
 * Fail-fast: as opções que desligam a validação de TLS/cadeia (só para dev e teste) NÃO podem ficar ligadas em hml/prd.
 * Sem isto, uma variável de ambiente esquecida abriria o serviço a ataque de intermediário sem nenhum aviso além de um log.
 */
@Configuration
public class InsecureTlsGuard {

    private static final List<String> FLAGS = List.of(
            "pix.webhook.insecure-tls", "pix.qr.insecure-tls", "pix.qr.allow-unverified-chain");

    private final Environment env;

    public InsecureTlsGuard(Environment env) {
        this.env = env;
    }

    @PostConstruct
    void validate() {
        if (!env.acceptsProfiles(Profiles.of("hml", "prd"))) {
            return;
        }
        for (String flag : FLAGS) {
            if (env.getProperty(flag, Boolean.class, false)) {
                throw new IllegalStateException(flag + "=true é permitido só em dev/teste: desligue-o em hml/prd");
            }
        }
    }
}
