package br.com.pixmanager.shared.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.time.ZoneId;

/**
 * Pix Automático (lado pagador). VALORES DE PROJETO, NÃO CONFIRMADOS no regulamento (docs/regulatory-verification.md): antecedência mínima e
 * máxima da cobrança (dias corridos antes do vencimento), dias de nova tentativa após o vencimento (só com {@code allow_retry}), atraso máximo
 * tolerado e validade da solicitação de autorização. Confira com o compliance antes de produção.
 */
@ConfigurationProperties(prefix = "pix.automatic")
public record AutomaticProperties(ZoneId zone, Integer minNoticeDays, Integer maxNoticeDays, Integer retryDays, Integer maxLateDays,
                                  Duration requestValidity, Duration stuckAfter, Integer batch) {

    public AutomaticProperties {
        zone = zone != null ? zone : ZoneId.of("America/Sao_Paulo");
        minNoticeDays = minNoticeDays != null ? minNoticeDays : 2;
        maxNoticeDays = maxNoticeDays != null ? maxNoticeDays : 10;
        retryDays = retryDays != null ? retryDays : 3;
        maxLateDays = maxLateDays != null ? maxLateDays : 1;
        requestValidity = requestValidity != null ? requestValidity : Duration.ofDays(7);
        stuckAfter = stuckAfter != null ? stuckAfter : Duration.ofMinutes(10);
        batch = batch != null ? batch : 20;
    }
}
