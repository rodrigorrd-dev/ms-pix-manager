package br.com.pixmanager.infrastructure.webhook;

import br.com.pixmanager.core.port.out.WebhookUrlPolicyPortOut.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Proteção anti-SSRF da URL do webhook")
class WebhookUrlPolicyAdapterTest {

    final WebhookUrlPolicyAdapter strict = new WebhookUrlPolicyAdapter(false, false);

    @ParameterizedTest(name = "{0} ⇒ bloqueado")
    @DisplayName("endereços internos, de metadados de nuvem e reservados são bloqueados (IP literal: não depende de DNS)")
    @ValueSource(strings = {
            "https://127.0.0.1/hook", "https://127.1.2.3/", "https://10.0.0.5/", "https://172.16.0.1/", "https://172.31.255.254/", "https://192.168.1.10/",
            "https://169.254.169.254/latest/meta-data/", "https://100.64.0.1/", "https://100.127.255.255/", "https://0.0.0.0/", "https://198.18.0.1/",
            "https://224.0.0.1/", "https://255.255.255.255/", "https://[::1]/", "https://[fd00::1]/", "https://[fe80::1]/", "https://[::]/",
            "https://[::ffff:10.0.0.1]/", "https://[::ffff:127.0.0.1]/", "https://localhost/hook"})
    void blocksInternalTargets(String url) {
        assertThat(strict.check(url)).isInstanceOf(Verdict.Forbidden.class);
    }

    @ParameterizedTest(name = "{0} ⇒ permitido")
    @DisplayName("endereços públicos passam (IPv4 e IPv6, com porta e caminho)")
    @ValueSource(strings = {"https://8.8.8.8/hook", "https://1.1.1.1:8443/pix/hook", "https://[2606:4700:4700::1111]/hook", "https://93.184.216.34/x?y=1"})
    void allowsPublicTargets(String url) {
        assertThat(strict.check(url).allowed()).isTrue();
    }

    @ParameterizedTest(name = "{0} ⇒ rejeitado")
    @DisplayName("esquema, credenciais, fragmento, host ausente, porta e tamanho")
    @ValueSource(strings = {"http://8.8.8.8/hook", "ftp://8.8.8.8/x", "file:///etc/passwd", "gopher://8.8.8.8/", "javascript:alert(1)", "//8.8.8.8/hook",
            "https://user:pass@8.8.8.8/hook", "https://user@8.8.8.8/hook", "https://8.8.8.8/hook#frag", "https:///semhost", "https://8.8.8.8:0/x",
            "https://8.8.8.8:70000/x", "", "   ", "https://exemplo .com/x"})
    void rejectsBadShapes(String url) {
        assertThat(strict.check(url).allowed()).isFalse();
    }

    @Test
    @DisplayName("URL nula ou maior que 2048 caracteres é rejeitada")
    void rejectsNullAndTooLong() {
        assertThat(strict.check(null).allowed()).isFalse();
        assertThat(strict.check("https://8.8.8.8/" + "a".repeat(2100)).allowed()).isFalse();
    }

    @Test
    @DisplayName("nome que não resolve ⇒ Unresolvable (não é o mesmo que proibido: no envio vira nova tentativa)")
    void unresolvable() {
        assertThat(strict.check("https://nao-existe-pix-teste.invalid/hook")).isInstanceOf(Verdict.Unresolvable.class);
    }

    @Test
    @DisplayName("flags de dev: allow-http aceita http; allow-private-hosts aceita loopback; um não implica o outro")
    void devFlags() {
        WebhookUrlPolicyAdapter httpOnly = new WebhookUrlPolicyAdapter(true, false);
        assertThat(httpOnly.check("http://8.8.8.8/hook").allowed()).isTrue();
        assertThat(httpOnly.check("http://127.0.0.1/hook").allowed()).isFalse();
        WebhookUrlPolicyAdapter privateOnly = new WebhookUrlPolicyAdapter(false, true);
        assertThat(privateOnly.check("https://127.0.0.1/hook").allowed()).isTrue();
        assertThat(privateOnly.check("http://127.0.0.1/hook").allowed()).isFalse();
        assertThat(new WebhookUrlPolicyAdapter(true, true).check("http://localhost:8080/hook").allowed()).isTrue();
    }
}
