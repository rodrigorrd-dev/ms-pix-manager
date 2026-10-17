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
    @DisplayName("hml/prd com allow-unverified-chain ligado recusam subir e nomeiam a propriedade")
    void recusaEmHmlEPrd() {
        for (String flag : new String[]{"pix.qr.allow-unverified-chain"}) {
            for (String profile : new String[]{"hml", "prd"}) {
                assertThatThrownBy(() -> new InsecureTlsGuard(env(flag, "true", profile)).validate())
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining(flag);
            }
        }
    }

    @Test
    @DisplayName("hml/prd com a opção desligada, e dev/teste com ela ligada, sobem")
    void sobeNosDemaisCasos() {
        assertThatCode(() -> new InsecureTlsGuard(env("pix.qr.allow-unverified-chain", "false", "prd")).validate()).doesNotThrowAnyException();
        assertThatCode(() -> new InsecureTlsGuard(new MockEnvironment()).validate()).doesNotThrowAnyException();
        assertThatCode(() -> new InsecureTlsGuard(env("pix.qr.allow-unverified-chain", "true", "test")).validate()).doesNotThrowAnyException();
    }
}
