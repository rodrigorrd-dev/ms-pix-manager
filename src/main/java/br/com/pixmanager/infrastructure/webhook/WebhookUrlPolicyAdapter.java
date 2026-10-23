package br.com.pixmanager.infrastructure.webhook;

import br.com.pixmanager.core.port.out.WebhookUrlPolicyPortOut;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * Anti-SSRF para a URL do webhook. Regras: somente {@code https} (o banco também exige: CHECK em {@code webhook_subscription.url}), sem credencial na URL, sem
 * fragmento, host presente e resolvível, e NENHUM endereço resolvido pode ser interno (loopback, link-local/metadados de nuvem 169.254.x, privado
 * RFC 1918, CGNAT 100.64/10, ULA fc00::/7, multicast, não especificado), exceto {@code pix.webhook.allow-private-hosts=true} (só dev/teste).
 *
 * <p>Limite conhecido (TOCTOU/DNS rebinding): a checagem resolve o nome, mas a conexão resolve de novo. Mitiga-se checando a CADA envio e não seguindo
 * redirecionamentos; o fechamento total exigiria fixar o IP validado na conexão (ou um egress proxy), o que fica para a rede de produção.
 */
@Component
public class WebhookUrlPolicyAdapter implements WebhookUrlPolicyPortOut {

    static final int MAX_URL_LENGTH = 2048;

    private final boolean allowPrivateHosts;

    public WebhookUrlPolicyAdapter(@Value("${pix.webhook.allow-private-hosts:false}") boolean allowPrivateHosts) {
        this.allowPrivateHosts = allowPrivateHosts;
    }

    @Override
    public Verdict check(String url) {
        if (url == null || url.isBlank() || url.length() > MAX_URL_LENGTH) {
            return new Verdict.Forbidden("URL vazia ou maior que " + MAX_URL_LENGTH + " caracteres");
        }
        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (java.net.URISyntaxException e) {
            return new Verdict.Forbidden("URL malformada");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!scheme.equals("https")) {
            return new Verdict.Forbidden("só https é aceito");
        }
        if (uri.getUserInfo() != null) {
            return new Verdict.Forbidden("a URL não pode conter usuário/senha");
        }
        if (uri.getFragment() != null) {
            return new Verdict.Forbidden("a URL não pode conter fragmento (#)");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return new Verdict.Forbidden("a URL precisa de um host");
        }
        if (uri.getPort() == 0 || uri.getPort() > 65535) {
            return new Verdict.Forbidden("porta inválida");
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException exception) {
            return new Verdict.Unresolvable(host);
        }
        if (!allowPrivateHosts) {
            for (InetAddress address : addresses) {
                if (isInternal(address)) {
                    return new Verdict.Forbidden("o host resolve para um endereço interno (" + address.getHostAddress() + ")");
                }
            }
        }
        return new Verdict.Allowed();
    }

    static boolean isInternal(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] addressBytes = address.getAddress();
        if (address instanceof Inet4Address) {
            int firstOctet = addressBytes[0] & 0xff;
            int secondOctet = addressBytes[1] & 0xff;
            return firstOctet == 0                                   // 0.0.0.0/8
                    || (firstOctet == 100 && secondOctet >= 64 && secondOctet <= 127)  // 100.64.0.0/10 (CGNAT)
                    || (firstOctet == 192 && secondOctet == 0 && (addressBytes[2] & 0xff) == 0)   // 192.0.0.0/24
                    || (firstOctet == 198 && (secondOctet == 18 || secondOctet == 19)) // 198.18.0.0/15 (benchmark)
                    || firstOctet >= 240;                            // reservado/broadcast
        }
        if (address instanceof Inet6Address) {
            return (addressBytes[0] & 0xfe) == 0xfc;                   // fc00::/7 (ULA); IPv4 mapeado já vem como Inet4Address
        }
        return false;
    }
}
