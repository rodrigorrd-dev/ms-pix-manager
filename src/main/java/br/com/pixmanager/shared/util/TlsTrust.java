package br.com.pixmanager.shared.util;

import org.springframework.util.ResourceUtils;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

/**
 * Contexto TLS que confia nas CAs padrão do JDK MAIS os certificados de um arquivo PEM/CRT (receptor com certificado
 * autoassinado em dev/teste, ou CA privada). A validação da cadeia e do nome do host continua valendo: nada aqui aceita
 * certificado arbitrário.
 */
public final class TlsTrust {

    private TlsTrust() {}

    /** @param location caminho do arquivo ({@code docker/mocks/dev-cert.pem}) ou {@code classpath:...} com um ou mais certificados X.509 */
    public static SSLContext withExtraCa(String location) {
        try {
            KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
            store.load(null, null);
            int index = 0;
            TrustManagerFactory defaults = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            defaults.init((KeyStore) null);
            for (TrustManager trustManager : defaults.getTrustManagers()) {
                if (trustManager instanceof X509TrustManager x509) {
                    for (X509Certificate issuer : x509.getAcceptedIssuers()) {
                        store.setCertificateEntry("default-" + index++, issuer);
                    }
                }
            }
            try (InputStream in = ResourceUtils.getURL(location).openStream()) {
                for (Certificate certificate : CertificateFactory.getInstance("X.509").generateCertificates(in)) {
                    store.setCertificateEntry("extra-" + index++, certificate);
                }
            }
            TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init(store);
            // nosemgrep: java.lang.security.audit.weak-ssl-context.weak-ssl-context -- "TLS" negocia 1.3/1.2 no JDK 21 (1.0/1.1 desativados)
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, factory.getTrustManagers(), new SecureRandom());
            return context;
        } catch (GeneralSecurityException | IOException exception) {
            throw new IllegalStateException("Certificado de confiança inválido ou ilegível: " + location, exception);
        }
    }
}
