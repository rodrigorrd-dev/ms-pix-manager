package br.com.pixmanager.shared.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * TODA regra regulatória parametrizável vive aqui (BR-GEN-05): nenhum prazo/limite hard-coded. Valores marcados [R] em
 * docs/09 vieram do solicitante e não foram conferidos no Regulamento — por isso são configuração, não constante.
 */
@ConfigurationProperties(prefix = "pix.policy")
public record PixPolicyProperties(
        @NestedConfigurationProperty Keys keys,
        @NestedConfigurationProperty Limits limits,
        @NestedConfigurationProperty Refund refund,
        @NestedConfigurationProperty Med med,
        @NestedConfigurationProperty Scheduling scheduling,
        @NestedConfigurationProperty Webhook webhook,
        @NestedConfigurationProperty Charge charge,
        @NestedConfigurationProperty Otp otp) {

    public PixPolicyProperties {
        keys = keys != null ? keys : new Keys(5, 20, Duration.ofDays(7), Duration.ofDays(30), Duration.ofSeconds(1));
        limits = limits != null ? limits : Limits.defaults();
        refund = refund != null ? refund : new Refund(Duration.ofDays(90));
        med = med != null ? med : new Med(Duration.ofDays(80), Duration.ofDays(7), Duration.ofDays(90), Duration.ofDays(80));
        scheduling = scheduling != null ? scheduling : new Scheduling(LocalTime.of(18, 0), LocalTime.of(21, 0));
        webhook = webhook != null ? webhook : new Webhook(8, Duration.ofSeconds(5), Duration.ofHours(6));
        charge = charge != null ? charge : new Charge(86_400);
        otp = otp != null ? otp : new Otp(Duration.ofMinutes(5), 5, Duration.ofSeconds(60), 10);
    }

    /**
     * Prova de posse por OTP (BR-KEY-04). Valores DE PROJETO (não vêm do regulamento): validade do código, tentativas por código, espera
     * entre pedidos e máximo de pedidos por chave em 24 h. Ajuste pelo risco/UX com o time de segurança.
     */
    public record Otp(Duration ttl, int maxAttempts, Duration resendCooldown, int maxPerDay) {
        public Otp {
            ttl = ttl != null ? ttl : Duration.ofMinutes(5);
            maxAttempts = maxAttempts > 0 ? maxAttempts : 5;
            resendCooldown = resendCooldown != null ? resendCooldown : Duration.ofSeconds(60);
            maxPerDay = maxPerDay > 0 ? maxPerDay : 10;
        }
    }

    /** Chaves por <b>conta</b> (não por pessoa): PF 5 / PJ 20; prazos de reivindicação. */
    public record Keys(int maxPerAccountIndividual, int maxPerAccountBusiness, Duration donorResponse, Duration claimerCompletion,
                       Duration activationMinAge) {

        public Keys {
            maxPerAccountIndividual = maxPerAccountIndividual > 0 ? maxPerAccountIndividual : 5;
            maxPerAccountBusiness = maxPerAccountBusiness > 0 ? maxPerAccountBusiness : 20;
            donorResponse = donorResponse != null ? donorResponse : Duration.ofDays(7);
            claimerCompletion = claimerCompletion != null ? claimerCompletion : Duration.ofDays(30);
            activationMinAge = activationMinAge != null ? activationMinAge : Duration.ofSeconds(1);
        }
    }

    public record Limits(ZoneId zone, LocalTime nightStart, LocalTime nightEnd, BigDecimal nightIndividual,
                         BigDecimal unregisteredDevicePerTx, BigDecimal unregisteredDeviceDaily,
                         BigDecimal defaultDailyIndividual, BigDecimal defaultDailyBusiness,
                         BigDecimal ceilingIndividual, BigDecimal ceilingBusiness,
                         Duration increaseDelayMin, Duration increaseDelayMax) {

        static Limits defaults() {
            return new Limits(null, null, null, null, null, null, null, null, null, null, null, null);
        }

        /** Valores padrão (decisões B2/D2 do solicitante); sobrescrevíveis por {@code pix.policy.limits.*}. */
        public Limits {
            zone = zone != null ? zone : ZoneId.of("America/Sao_Paulo");
            nightStart = nightStart != null ? nightStart : LocalTime.of(20, 0);
            nightEnd = nightEnd != null ? nightEnd : LocalTime.of(6, 0);
            nightIndividual = nightIndividual != null ? nightIndividual : new BigDecimal("1000.00");
            unregisteredDevicePerTx = unregisteredDevicePerTx != null ? unregisteredDevicePerTx : new BigDecimal("200.00");
            unregisteredDeviceDaily = unregisteredDeviceDaily != null ? unregisteredDeviceDaily : new BigDecimal("1000.00");
            defaultDailyIndividual = defaultDailyIndividual != null ? defaultDailyIndividual : new BigDecimal("5000.00");
            defaultDailyBusiness = defaultDailyBusiness != null ? defaultDailyBusiness : new BigDecimal("50000.00");
            // teto do banco: premissa (docs/10) — 10× o diário padrão, configurável; o cliente nunca ultrapassa (BR-LIM-04)
            ceilingIndividual = ceilingIndividual != null ? ceilingIndividual : new BigDecimal("50000.00");
            ceilingBusiness = ceilingBusiness != null ? ceilingBusiness : new BigDecimal("500000.00");
            increaseDelayMin = increaseDelayMin != null ? increaseDelayMin : Duration.ofHours(24);
            increaseDelayMax = increaseDelayMax != null ? increaseDelayMax : Duration.ofHours(48);
        }
    }

    /** Janela de devolução comum (a partir da transação original). */
    public record Refund(Duration window) {}

    /** MED: abertura (80 d), análise (7 d), janela de rastreamento (90 d) e contestação pelo recebedor (80 d da devolução). */
    public record Med(Duration openWindow, Duration analysis, Duration trackingWindow, Duration contestationWindow) {}

    /** Retentativa do Pix Agendado por saldo insuficiente: mesmo dia, entre 18h e 21h (BR-SCH-03). */
    public record Scheduling(LocalTime retryWindowStart, LocalTime retryWindowEnd) {}

    /** Entrega de webhook: 8 tentativas, backoff exponencial com jitter de 5 s a 6 h (BR-WHK-03). */
    public record Webhook(int maxAttempts, Duration minDelay, Duration maxDelay) {}

    public record Charge(int defaultExpirationSeconds) {}
}
