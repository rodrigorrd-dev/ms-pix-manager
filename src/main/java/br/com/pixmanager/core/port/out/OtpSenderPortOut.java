package br.com.pixmanager.core.port.out;

import br.com.pixmanager.core.domain.enums.OtpChannel;

/**
 * Entrega do código ao dono do e-mail/telefone, via serviço de notificação EXTERNO (como dict-gateway e core). Falha técnica ⇒
 * {@code IntegrationException}. O código sai daqui em claro e NUNCA deve ser logado pela implementação de produção.
 */
public interface OtpSenderPortOut {

    void send(OtpChannel channel, String destination, String code, java.time.Duration validFor);
}
