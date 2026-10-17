package br.com.pixmanager.shared.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InsecureTlsGuardTest {

    private static MockEnvironment env(String flag, String value, String... profiles) {
        MockEnvironment env = new MockEnvironment().withProperty(flag, value);
        env.setActiveProfiles(profiles);
        return env;
    }

    @Test
    @DisplayName("hml/prd com insecure-tls ou allow-unverified-chain ligado recusam subir e nomeiam a propriedade")
    void recusaEmHmlEPrd() {
        for (String flag : new String[]{"pix.webhook.insecure-tls", "pix.qr.insecure-tls", "pix.qr.allow-unverified-chain"}) {
            for (String profile : new String[]{"hml", "prd"}) {
                assertThatThrownBy(() -> new InsecureTlsGuard(env(flag, "true", profile)).validate())
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining(flag);
            }
        }
    }

    @Test
    @DisplayName("hml/prd com as opções desligadas, e dev/teste com elas ligadas, sobem")
    void sobeNosDemaisCasos() {
        assertThatCode(() -> new InsecureTlsGuard(env("pix.qr.insecure-tls", "false", "prd")).validate()).doesNotThrowAnyException();
        assertThatCode(() -> new InsecureTlsGuard(new MockEnvironment()).validate()).doesNotThrowAnyException();
        assertThatCode(() -> new InsecureTlsGuard(env("pix.webhook.insecure-tls", "true", "local")).validate()).doesNotThrowAnyException();
        assertThatCode(() -> new InsecureTlsGuard(env("pix.qr.allow-unverified-chain", "true", "test")).validate()).doesNotThrowAnyException();
    }
}
