package br.com.pixmanager.infrastructure.qr;

import br.com.pixmanager.shared.util.TlsTrust;
import br.com.pixmanager.core.domain.entity.Money;
import br.com.pixmanager.core.port.out.DynamicQrPayloadPortOut;
import br.com.pixmanager.core.port.out.WebhookUrlPolicyPortOut;
import br.com.pixmanager.shared.constant.ErrorCodes;
import br.com.pixmanager.shared.exception.BusinessException;
import br.com.pixmanager.shared.exception.DependencyUnavailableException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyStore;
import java.security.cert.CertPath;
import java.security.cert.CertPathValidator;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.cert.PKIXParameters;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * Busca o payload JWS do QR dinâmico e o VALIDA antes de qualquer pagamento. Defesas, na ordem:
 * (1) a URL vem do QR (entrada do usuário) ⇒ política anti-SSRF (só https, host público) e sem seguir redirecionamentos;
 * (2) corpo limitado e timeouts curtos; (3) JWS com algoritmo RS256/PS256/ES256 e cadeia {@code x5c} no cabeçalho;
 * (4) assinatura conferida com a chave do certificado folha; (5) cadeia validada contra a trust store configurada
 * (ICP-Brasil em produção) — sem trust store o adaptador FALHA FECHADO, a menos que {@code allow-unverified-chain} (só dev).
 * Revogação (CRL/OCSP) NÃO é verificada: está documentada como pendência em docs/pendencias-funcionais.md.
 */
@Component
public class HttpDynamicQrPayloadAdapter implements DynamicQrPayloadPortOut {

    private static final Logger log = LoggerFactory.getLogger(HttpDynamicQrPayloadAdapter.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ZoneId BRASILIA = ZoneId.of("America/Sao_Paulo");
    private static final List<JWSAlgorithm> ALLOWED = List.of(JWSAlgorithm.RS256, JWSAlgorithm.PS256, JWSAlgorithm.ES256);

    private final HttpClient client;
    private final WebhookUrlPolicyPortOut urlPolicy;
    private final Duration requestTimeout;
    private final int maxBytes;
    private final KeyStore trustStore;
    private final boolean allowUnverifiedChain;

    public HttpDynamicQrPayloadAdapter(WebhookUrlPolicyPortOut urlPolicy,
                                       @Value("${pix.qr.connect-timeout:3s}") Duration connectTimeout,
                                       @Value("${pix.qr.request-timeout:5s}") Duration requestTimeout,
                                       @Value("${pix.qr.max-bytes:65536}") int maxBytes,
                                       @Value("${pix.qr.trust-store-path:}") String trustStorePath,
                                       @Value("${pix.qr.trust-store-password:}") String trustStorePassword,
                                       @Value("${pix.qr.trust-store-type:PKCS12}") String trustStoreType,
                                       @Value("${pix.qr.allow-unverified-chain:false}") boolean allowUnverifiedChain,
                                       @Value("${pix.qr.tls-ca-path:}") String tlsCaPath) {
        this.urlPolicy = urlPolicy;
        this.requestTimeout = requestTimeout;
        this.maxBytes = maxBytes;
        this.allowUnverifiedChain = allowUnverifiedChain;
        this.trustStore = loadTrustStore(trustStorePath, trustStorePassword, trustStoreType);
        if (trustStore == null && allowUnverifiedChain) {
            log.warn("pix.qr.allow-unverified-chain=true: a cadeia de certificados do QR dinâmico NÃO é validada. SÓ dev/teste");
        } else if (trustStore == null) {
            log.warn("QR dinâmico sem trust store (pix.qr.trust-store-path): todo pagamento por QR dinâmico será recusado até configurá-la");
        }
        HttpClient.Builder builder = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER);
        if (tlsCaPath != null && !tlsCaPath.isBlank()) {
            builder.sslContext(TlsTrust.withExtraCa(tlsCaPath));   // CA/certificado extra do recebedor (dev/teste ou CA privada); a cadeia e o host continuam validados
        }
        this.client = builder.build();
    }

    @Override
    public DynamicQrPayload fetch(String paymentUrl) {
        String url = paymentUrl == null ? "" : paymentUrl.trim();
        if (url.contains("://")) {
            throw invalid("A URL do QR dinâmico não deve trazer esquema.");
        }
        url = "https://" + url;                                                       // o BR Code carrega a URL SEM esquema
        var verdict = urlPolicy.check(url);
        if (!verdict.allowed()) {
            throw invalid("A URL do QR dinâmico foi recusada: " + verdict.describe() + ".");
        }
        String jws = download(url);
        return validate(jws);
    }

    private String download(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(requestTimeout)
                    .header("Accept", "application/jose, application/json").header("User-Agent", "ms-pix-manager").GET().build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body()) {
                int status = response.statusCode();
                if (status == 404 || status == 410) {
                    throw invalid("Cobrança do QR não encontrada no recebedor.");
                }
                if (status >= 500 || status == 429) {
                    throw new DependencyUnavailableException("Recebedor do QR dinâmico respondeu " + status, null);
                }
                if (status != 200) {
                    throw invalid("O recebedor do QR dinâmico recusou a consulta (HTTP " + status + ").");
                }
                byte[] body = in.readNBytes(maxBytes + 1);
                if (body.length > maxBytes) {
                    throw invalid("O payload do QR dinâmico excede o tamanho máximo.");
                }
                return new String(body, java.nio.charset.StandardCharsets.UTF_8).trim();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new DependencyUnavailableException("Consulta ao recebedor do QR dinâmico interrompida", exception);
        } catch (IOException exception) {
            throw new DependencyUnavailableException("Recebedor do QR dinâmico indisponível: " + exception.getClass().getSimpleName(), exception);
        }
    }

    DynamicQrPayload validate(String compactJws) {
        JWSObject jws;
        try {
            jws = JWSObject.parse(compactJws);
        } catch (java.text.ParseException e) {
            throw invalid("O payload do QR dinâmico não é um JWS válido.");
        }
        if (!ALLOWED.contains(jws.getHeader().getAlgorithm())) {
            throw invalid("Algoritmo de assinatura não aceito: " + jws.getHeader().getAlgorithm() + ".");
        }
        List<X509Certificate> chain = certificateChain(jws.getHeader().getX509CertChain());
        verifySignature(jws, chain.get(0));
        verifyChain(chain);
        return parsePayload(jws.getPayload().toString());
    }

    private static List<X509Certificate> certificateChain(List<Base64> x5c) {
        if (x5c == null || x5c.isEmpty()) {
            throw invalid("O JWS do QR dinâmico não traz a cadeia de certificados (x5c).");
        }
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            var chain = new java.util.ArrayList<X509Certificate>();
            for (Base64 der : x5c) {
                chain.add((X509Certificate) factory.generateCertificate(new ByteArrayInputStream(der.decode())));
            }
            chain.get(0).checkValidity();
            return chain;
        } catch (java.security.GeneralSecurityException | RuntimeException exception) {
            throw invalid("Certificado do QR dinâmico inválido ou fora da validade.");
        }
    }

    private static void verifySignature(JWSObject jws, X509Certificate leaf) {
        try {
            JWSVerifier verifier = leaf.getPublicKey() instanceof RSAPublicKey rsa ? new RSASSAVerifier(rsa)
                    : leaf.getPublicKey() instanceof ECPublicKey ec ? new ECDSAVerifier(ec) : null;
            if (verifier == null || !jws.verify(verifier)) {
                throw invalid("Assinatura do QR dinâmico inválida.");
            }
        } catch (com.nimbusds.jose.JOSEException e) {
            throw invalid("Assinatura do QR dinâmico não pôde ser verificada.");
        }
    }

    private void verifyChain(List<X509Certificate> chain) {
        if (trustStore == null) {
            if (allowUnverifiedChain) {
                return;
            }
            throw new BusinessException(ErrorCodes.QR_DYNAMIC_INVALID, "QR dinâmico indisponível: a trust store de certificados não está configurada.");
        }
        try {
            CertPath path = CertificateFactory.getInstance("X.509").generateCertPath(chain.subList(0, chain.size()));
            PKIXParameters params = new PKIXParameters(trustStore);
            params.setRevocationEnabled(false);                                       // CRL/OCSP: pendência documentada
            CertPathValidator.getInstance("PKIX").validate(path, params);
        } catch (java.security.GeneralSecurityException e) {
            throw invalid("A cadeia de certificados do QR dinâmico não é confiável.");
        }
    }

    static DynamicQrPayload parsePayload(String json) {
        try {
            JsonNode node = JSON.readTree(json);
            String txid = text(node, "txid");
            String key = text(node, "chave");
            String status = text(node, "status");
            JsonNode valor = node.path("valor");
            String amount = valor.hasNonNull("final") ? valor.get("final").asText() : text(valor, "original");
            if (txid == null || key == null || status == null || amount == null) {
                throw invalid("O payload do QR dinâmico não traz txid, chave, status e valor.");
            }
            Money money = Money.parseBcb(amount);
            if (!money.isPositive()) {
                throw invalid("O valor da cobrança do QR dinâmico deve ser maior que zero.");
            }
            return new DynamicQrPayload(txid, key, money, status, expiry(node.path("calendario")));
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof BusinessException businessException) {
                throw businessException;
            }
            throw invalid("O payload do QR dinâmico é ilegível.");
        }
    }

    /** cob: criação + expiração (s, padrão 86400). cobv: vale até o fim do dia (Brasília) de vencimento + validadeAposVencimento. */
    private static Instant expiry(JsonNode cal) {
        if (cal.hasNonNull("dataDeVencimento")) {
            LocalDate due = LocalDate.parse(cal.get("dataDeVencimento").asText());
            long grace = cal.path("validadeAposVencimento").asLong(0);
            return due.plusDays(grace + 1).atStartOfDay(BRASILIA).toInstant();
        }
        if (cal.hasNonNull("criacao")) {
            return Instant.parse(cal.get("criacao").asText()).plusSeconds(cal.path("expiracao").asLong(86_400));
        }
        return null;
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) && !node.get(field).asText().isBlank() ? node.get(field).asText() : null;
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCodes.QR_DYNAMIC_INVALID, message);
    }

    private static KeyStore loadTrustStore(String path, String password, String type) {
        if (path == null || path.isBlank()) {
            return null;
        }
        try (InputStream in = new FileInputStream(path)) {
            KeyStore store = KeyStore.getInstance(type);
            store.load(in, password == null ? null : password.toCharArray());
            return store;
        } catch (IOException | java.security.GeneralSecurityException e) {
            throw new IllegalStateException("Não foi possível ler a trust store do QR dinâmico (pix.qr.trust-store-path)", e);
        }
    }

}
