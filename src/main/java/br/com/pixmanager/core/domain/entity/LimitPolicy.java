package br.com.pixmanager.core.domain.entity;

import br.com.pixmanager.core.domain.enums.HolderType;
import br.com.pixmanager.core.domain.enums.LimitScope;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Optional;

/**
 * Regras de limite SEM I/O (BR-LIM-01/02/04). O limite que prevalece é o MENOR dos aplicáveis.
 * <ul>
 *   <li>Noturno ({@code nightStart}–{@code nightEnd}, fuso configurado): só PF, valor por transação; PJ não é afetada;</li>
 *   <li>Dispositivo não cadastrado: por transação e por dia (valores menores);</li>
 *   <li>Diário: configurado pela conta ou o padrão por tipo de titular (política interna).</li>
 * </ul>
 * O consumo do limite DIÁRIO é atômico em outro passo (UPDATE condicional); aqui só se decide o quê checar.
 */
public final class LimitPolicy {

    /** Parâmetros regulatórios/de política (de {@code PixPolicyProperties}; nunca constantes no código). */
    public record Config(ZoneId zone, LocalTime nightStart, LocalTime nightEnd, Money nightIndividual,
                         Money unregisteredDevicePerTx, Money unregisteredDeviceDaily, Money defaultDailyIndividual,
                         Money defaultDailyBusiness) {}

    public sealed interface PerTransactionDecision {
        record Allowed() implements PerTransactionDecision {}

        record Rejected(LimitScope scope, Money limit) implements PerTransactionDecision {}
    }

    /** O que checar de forma ATÔMICA: limite diário e (se aplicável) diário de dispositivo não cadastrado. */
    public record DailyChecks(LocalDate day, Money dailyLimit, Optional<Money> deviceDailyLimit) {}

    private LimitPolicy() {}

    /** {@code [nightStart, nightEnd)} atravessando a meia-noite (20h–6h). */
    public static boolean isNight(Instant at, Config config) {
        LocalTime time = at.atZone(config.zone()).toLocalTime();
        boolean crossesMidnight = config.nightStart().isAfter(config.nightEnd());
        return crossesMidnight
                ? !time.isBefore(config.nightStart()) || time.isBefore(config.nightEnd())
                : !time.isBefore(config.nightStart()) && time.isBefore(config.nightEnd());
    }

    public static PerTransactionDecision checkPerTransaction(HolderType holder, Money amount, Instant at, boolean deviceRegistered,
                                                             Map<LimitScope, Money> configured, Config config) {
        Money perTransactionLimit = configured.get(LimitScope.PER_TRANSACTION);
        if (perTransactionLimit != null && amount.isGreaterThan(perTransactionLimit)) {
            return new PerTransactionDecision.Rejected(LimitScope.PER_TRANSACTION, perTransactionLimit);
        }
        if (holder == HolderType.INDIVIDUAL && isNight(at, config)) {
            Money night = configuredOr(config.nightIndividual(), configured.get(LimitScope.NIGHTLY));
            if (amount.isGreaterThan(night)) {
                return new PerTransactionDecision.Rejected(LimitScope.NIGHTLY, night);
            }
        }
        if (!deviceRegistered) {
            Money device = configuredOr(config.unregisteredDevicePerTx(), configured.get(LimitScope.DEVICE_UNREGISTERED_PER_TX));
            if (amount.isGreaterThan(device)) {
                return new PerTransactionDecision.Rejected(LimitScope.DEVICE_UNREGISTERED_PER_TX, device);
            }
        }
        return new PerTransactionDecision.Allowed();
    }

    public static DailyChecks dailyChecks(HolderType holder, Instant at, boolean deviceRegistered, Map<LimitScope, Money> configured,
                                          Config config) {
        Money daily = configured.getOrDefault(LimitScope.DAILY,
                holder == HolderType.INDIVIDUAL ? config.defaultDailyIndividual() : config.defaultDailyBusiness());
        Optional<Money> device = deviceRegistered ? Optional.empty()
                : Optional.of(configuredOr(config.unregisteredDeviceDaily(), configured.get(LimitScope.DEVICE_UNREGISTERED_DAILY)));
        ZonedDateTime local = at.atZone(config.zone());
        return new DailyChecks(local.toLocalDate(), daily, device);
    }

    /**
     * O valor do cliente SUBSTITUI o padrão regulatório (o cliente pode reduzir de imediato ou aumentar após a carência, até o
     * teto do banco — o teto é imposto em {@code AccountLimit.requestChange}, não aqui). Entre escopos diferentes prevalece o MENOR.
     */
    private static Money configuredOr(Money regulatoryDefault, Money configured) {
        return configured == null ? regulatoryDefault : configured;
    }
}
