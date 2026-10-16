package br.com.pixmanager.core.application.service.pixkey;

import br.com.pixmanager.core.domain.entity.PixKey;
import br.com.pixmanager.core.domain.entity.PixKeyValue;
import br.com.pixmanager.core.domain.entity.event.PixKeyEvent;
import br.com.pixmanager.core.domain.enums.PixKeyStatus;
import br.com.pixmanager.core.domain.enums.PixKeyType;
import br.com.pixmanager.core.port.out.dict.DictGatewayPortOut;
import br.com.pixmanager.core.port.out.dict.DictGatewayPortOut.DictRegistration;
import br.com.pixmanager.core.port.out.event.DomainEventPublisherPortOut;
import br.com.pixmanager.core.port.out.persistence.PixKeyPersistencePortOut;
import br.com.pixmanager.core.port.out.persistence.TransactionRunnerPortOut;
import br.com.pixmanager.shared.exception.BusinessException;
import br.com.pixmanager.shared.exception.IntegrationException;
import br.com.pixmanager.support.FixedClock;
import br.com.pixmanager.support.SequentialIds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ActivatePendingKeysService — DICT fora da TX; negócio ⇒ REJECTED; técnico ⇒ segue PENDING_DICT; uma chave ruim não trava as outras")
class ActivatePendingKeysServiceTest {

    static final Instant NOW = Instant.parse("2026-10-15T15:00:00Z");
    static final Duration MIN_AGE = Duration.ofSeconds(5);

    @Mock PixKeyPersistencePortOut persistence;
    @Mock DictGatewayPortOut dict;
    @Mock DomainEventPublisherPortOut events;

    ActivatePendingKeysService service;

    @BeforeEach
    void setUp() {
        TransactionRunnerPortOut tx = new TransactionRunnerPortOut() {
            @Override
            public <T> T inTransaction(Supplier<T> action) {
                return action.get();
            }
        };
        service = new ActivatePendingKeysService(persistence, dict, events, tx, FixedClock.at("2026-10-15T15:00:00Z"), MIN_AGE);
    }

    @Test
    void shouldActivateWithTheCidAndPublishPixKeyActivated() {
        PixKey pending = pending(1);
        when(persistence.findPendingDict(NOW.minus(MIN_AGE), 10)).thenReturn(List.of(pending));
        when(dict.register(pending)).thenReturn(new DictRegistration("cid-1", NOW));
        when(persistence.save(any(PixKey.class))).thenAnswer(inv -> inv.getArgument(0));

        int activated = service.activatePending(10);

        assertThat(activated).isEqualTo(1);
        var saved = ArgumentCaptor.forClass(PixKey.class);
        verify(persistence).save(saved.capture());
        assertThat(saved.getValue().status()).isEqualTo(PixKeyStatus.ACTIVE);
        assertThat(saved.getValue().dictCid()).isEqualTo("cid-1");
        assertThat(publishedEvents()).containsExactly("PixKeyActivated");
    }

    @Test
    @DisplayName("DICT recusou (negócio): REJECTED definitivo e não conta como ativada")
    void shouldRejectTheKeyWhenTheDictRefusesIt() {
        PixKey pending = pending(1);
        when(persistence.findPendingDict(any(), eq(10))).thenReturn(List.of(pending));
        when(dict.register(pending)).thenThrow(new BusinessException("PIX-422-012", "chave inválida no DICT"));
        when(persistence.save(any(PixKey.class))).thenAnswer(inv -> inv.getArgument(0));

        int activated = service.activatePending(10);

        assertThat(activated).isZero();
        var saved = ArgumentCaptor.forClass(PixKey.class);
        verify(persistence).save(saved.capture());
        assertThat(saved.getValue().status()).isEqualTo(PixKeyStatus.REJECTED);
        assertThat(publishedEvents()).containsExactly("PixKeyRejected");
    }

    @Test
    @DisplayName("DICT indisponível (técnico): NADA é gravado nem publicado; a chave volta na próxima rodada")
    void shouldLeaveTheKeyPendingWhenTheDictIsUnavailable() {
        PixKey pending = pending(1);
        when(persistence.findPendingDict(any(), eq(10))).thenReturn(List.of(pending));
        when(dict.register(pending)).thenThrow(new IntegrationException("PIX-502-001", "timeout", null));

        int activated = service.activatePending(10);

        assertThat(activated).isZero();
        verify(persistence, never()).save(any());
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("Falha ao GRAVAR localmente nunca vira REJECTED, e a rodada segue com as demais chaves")
    void shouldKeepProcessingTheRemainingKeysWhenOneFailsUnexpectedly() {
        PixKey broken = pending(1);
        PixKey healthy = pending(2);
        when(persistence.findPendingDict(any(), eq(10))).thenReturn(List.of(broken, healthy));
        when(dict.register(broken)).thenReturn(new DictRegistration("cid-1", NOW));
        when(dict.register(healthy)).thenReturn(new DictRegistration("cid-2", NOW));
        when(persistence.save(any(PixKey.class)))
                .thenThrow(new IllegalStateException("banco caiu"))
                .thenAnswer(inv -> inv.getArgument(0));

        int activated = service.activatePending(10);

        assertThat(activated).isEqualTo(1);
        verify(persistence, times(2)).save(any(PixKey.class));
        assertThat(publishedEvents()).as("só a chave saudável gerou evento").containsExactly("PixKeyActivated");
    }

    @Test
    void shouldDoNothingWhenThereAreNoPendingKeys() {
        when(persistence.findPendingDict(any(), eq(10))).thenReturn(List.of());

        assertThat(service.activatePending(10)).isZero();

        verifyNoInteractions(dict, events);
    }

    // ------------------------------------------------------------------ helpers

    private static PixKey pending(long n) {
        return PixKey.register(SequentialIds.id(n), SequentialIds.id(10), PixKeyValue.of(PixKeyType.EVP, "0000000" + n + "-0000-0000-0000-000000000000"), NOW);
    }

    private List<String> publishedEvents() {
        var captor = ArgumentCaptor.forClass(PixKeyEvent.class);
        verify(events, org.mockito.Mockito.atLeast(0)).publish(captor.capture());
        return captor.getAllValues().stream().map(PixKeyEvent::eventType).toList();
    }
}
