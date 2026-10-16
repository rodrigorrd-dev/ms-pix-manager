package br.com.pixmanager.shared.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.time.ZoneId;

/**
 * Execução do Pix Agendado. A janela de retentativa por saldo (18h–21h) fica em {@code pix.policy.scheduling}. Os demais valores são
 * DE PROJETO, não do regulamento: horizonte máximo de agendamento, atraso tolerado na execução, tentativas técnicas.
 */
@ConfigurationProperties(prefix = "pix.schedule")
public record ScheduleProperties(ZoneId zone, Integer maxHorizonDays, Integer maxLateDays, Integer maxTechnicalAttempts,
                                 Duration technicalBackoff, Duration stuckAfter, Integer batch) {

    public ScheduleProperties {
        zone = zone != null ? zone : ZoneId.of("America/Sao_Paulo");
        maxHorizonDays = maxHorizonDays != null ? maxHorizonDays : 730;
        maxLateDays = maxLateDays != null ? maxLateDays : 3;
        maxTechnicalAttempts = maxTechnicalAttempts != null ? maxTechnicalAttempts : 5;
        technicalBackoff = technicalBackoff != null ? technicalBackoff : Duration.ofMinutes(2);
        stuckAfter = stuckAfter != null ? stuckAfter : Duration.ofMinutes(10);
        batch = batch != null ? batch : 20;
    }
}
