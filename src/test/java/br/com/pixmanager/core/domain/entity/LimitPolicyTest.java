package br.com.pixmanager.core.domain.entity;

import br.com.pixmanager.core.domain.entity.LimitPolicy.Config;
import br.com.pixmanager.core.domain.entity.LimitPolicy.PerTransactionDecision;
import br.com.pixmanager.core.domain.enums.HolderType;
import br.com.pixmanager.core.domain.enums.LimitScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.EnumMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LimitPolicy — BR-LIM-01/02/04: noturno PF, dispositivo não cadastrado, menor limite prevalece")
class LimitPolicyTest {

    static final ZoneId SP = ZoneId.of("America/Sao_Paulo");
    static final Config CFG = new Config(SP, LocalTime.of(20, 0), LocalTime.of(6, 0), Money.of("1000.00"), Money.of("200.00"),
            Money.of("1000.00"), Money.of("5000.00"), Money.of("50000.00"));

    static Instant at(String localDateTime) {
        return ZonedDateTime.of(java.time.LocalDateTime.parse(localDateTime), SP).toInstant();
    }

    @ParameterizedTest(name = "{0} em America/Sao_Paulo ⇒ noite={1}")
    @CsvSource({"2026-10-15T19:59:59,false", "2026-10-15T20:00:00,true", "2026-10-15T23:59:59,true", "2026-10-16T00:00:00,true",
            "2026-10-16T05:59:59,true", "2026-10-16T06:00:00,false", "2026-10-16T12:00:00,false"})
    void nightWindowCrossesMidnightWithExactBoundaries(String local, boolean night) {
        assertThat(LimitPolicy.isNight(at(local), CFG)).isEqualTo(night);
    }

    @Test
    void nightWindowIsEvaluatedInTheConfiguredZoneNotInUtc() {
        // 22:30 em São Paulo = 01:30 UTC do dia seguinte: continua noite em SP
        assertThat(LimitPolicy.isNight(Instant.parse("2026-10-16T01:30:00Z"), CFG)).isTrue();
        // 12:00 UTC = 09:00 em SP: dia
        assertThat(LimitPolicy.isNight(Instant.parse("2026-10-15T12:00:00Z"), CFG)).isFalse();
    }

    @Test
    @DisplayName("PF à noite: R$1.000 passa, R$1.000,01 é recusado pelo escopo NIGHTLY")
    void individualAtNight() {
        Instant night = at("2026-10-15T21:00:00");
        assertThat(LimitPolicy.checkPerTransaction(HolderType.INDIVIDUAL, Money.of("1000.00"), night, true, Map.of(), CFG))
                .isInstanceOf(PerTransactionDecision.Allowed.class);
        assertThat(LimitPolicy.checkPerTransaction(HolderType.INDIVIDUAL, Money.of("1000.01"), night, true, Map.of(), CFG))
                .isEqualTo(new PerTransactionDecision.Rejected(LimitScope.NIGHTLY, Money.of("1000.00")));
    }

    @Test
    @DisplayName("PJ NÃO é afetada pelo limite noturno")
    void businessIsNotSubjectToNightLimit() {
        assertThat(LimitPolicy.checkPerTransaction(HolderType.BUSINESS, Money.of("20000.00"), at("2026-10-15T21:00:00"), true, Map.of(), CFG))
                .isInstanceOf(PerTransactionDecision.Allowed.class);
    }

    @Test
    @DisplayName("PF de dia: sem limite noturno")
    void individualAtDaytime() {
        assertThat(LimitPolicy.checkPerTransaction(HolderType.INDIVIDUAL, Money.of("4000.00"), at("2026-10-15T10:00:00"), true, Map.of(), CFG))
                .isInstanceOf(PerTransactionDecision.Allowed.class);
    }

    @Test
    @DisplayName("Aumento do noturno pelo cliente (já vigente) SUBSTITUI o padrão de R$1.000")
    void customerRaisedNightLimitReplacesDefault() {
        Map<LimitScope, Money> configured = new EnumMap<>(LimitScope.class);
        configured.put(LimitScope.NIGHTLY, Money.of("3000.00"));
        assertThat(LimitPolicy.checkPerTransaction(HolderType.INDIVIDUAL, Money.of("2500.00"), at("2026-10-15T22:00:00"), true, configured, CFG))
                .isInstanceOf(PerTransactionDecision.Allowed.class);
        assertThat(LimitPolicy.checkPerTransaction(HolderType.INDIVIDUAL, Money.of("3000.01"), at("2026-10-15T22:00:00"), true, configured, CFG))
                .isInstanceOf(PerTransactionDecision.Rejected.class);
    }

    @Test
    @DisplayName("Dispositivo não cadastrado: R$200 por transação e R$1.000 por dia; o MENOR limite prevalece")
    void unregisteredDevice() {
        Instant day = at("2026-10-15T10:00:00");
        assertThat(LimitPolicy.checkPerTransaction(HolderType.INDIVIDUAL, Money.of("200.00"), day, false, Map.of(), CFG))
                .isInstanceOf(PerTransactionDecision.Allowed.class);
        assertThat(LimitPolicy.checkPerTransaction(HolderType.INDIVIDUAL, Money.of("200.01"), day, false, Map.of(), CFG))
                .isEqualTo(new PerTransactionDecision.Rejected(LimitScope.DEVICE_UNREGISTERED_PER_TX, Money.of("200.00")));
        // PJ também sofre a regra do dispositivo (não é a noturna)
        assertThat(LimitPolicy.checkPerTransaction(HolderType.BUSINESS, Money.of("500.00"), day, false, Map.of(), CFG))
                .isInstanceOf(PerTransactionDecision.Rejected.class);
        // à noite, PF com dispositivo novo: o noturno (1000) é maior que o do dispositivo (200) ⇒ vale o do dispositivo
        assertThat(LimitPolicy.checkPerTransaction(HolderType.INDIVIDUAL, Money.of("300.00"), at("2026-10-15T21:00:00"), false, Map.of(), CFG))
                .isEqualTo(new PerTransactionDecision.Rejected(LimitScope.DEVICE_UNREGISTERED_PER_TX, Money.of("200.00")));
    }

    @Test
    void perTransactionConfiguredByCustomerIsEnforced() {
        Map<LimitScope, Money> configured = Map.of(LimitScope.PER_TRANSACTION, Money.of("800.00"));
        assertThat(LimitPolicy.checkPerTransaction(HolderType.BUSINESS, Money.of("800.01"), at("2026-10-15T10:00:00"), true, configured, CFG))
                .isEqualTo(new PerTransactionDecision.Rejected(LimitScope.PER_TRANSACTION, Money.of("800.00")));
    }

    @Test
    @DisplayName("Diário: padrão 5.000 (PF) / 50.000 (PJ); dia = America/Sao_Paulo (23h SP ainda é o MESMO dia)")
    void dailyDefaultsAndDayInLocalZone() {
        var pf = LimitPolicy.dailyChecks(HolderType.INDIVIDUAL, at("2026-10-15T23:30:00"), true, Map.of(), CFG);
        assertThat(pf.dailyLimit()).isEqualTo(Money.of("5000.00"));
        assertThat(pf.day()).isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(pf.deviceDailyLimit()).isEmpty();
        assertThat(LimitPolicy.dailyChecks(HolderType.BUSINESS, at("2026-10-15T10:00:00"), true, Map.of(), CFG).dailyLimit())
                .isEqualTo(Money.of("50000.00"));
        assertThat(LimitPolicy.dailyChecks(HolderType.INDIVIDUAL, at("2026-10-15T10:00:00"), false, Map.of(), CFG).deviceDailyLimit())
                .contains(Money.of("1000.00"));
        // 02:00 UTC de 16/10 ainda é 23:00 de 15/10 em SP
        assertThat(LimitPolicy.dailyChecks(HolderType.INDIVIDUAL, Instant.parse("2026-10-16T02:00:00Z"), true, Map.of(), CFG).day())
                .isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(ZonedDateTime.ofInstant(Instant.parse("2026-10-16T02:00:00Z"), SP).getHour()).isEqualTo(23);
    }
}
