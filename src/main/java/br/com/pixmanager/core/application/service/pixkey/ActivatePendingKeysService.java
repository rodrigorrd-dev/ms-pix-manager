package br.com.pixmanager.core.application.service.pixkey;

import br.com.pixmanager.core.port.in.pixkey.ActivatePendingKeysUseCase;
import br.com.pixmanager.core.port.out.dict.DictGatewayPortOut;
import br.com.pixmanager.core.port.out.dict.DictGatewayPortOut.DictRegistration;
import br.com.pixmanager.core.port.out.persistence.PixKeyPersistencePortOut;
import br.com.pixmanager.core.domain.entity.PixKey;
import br.com.pixmanager.core.domain.entity.event.PixKeyEvent;
import br.com.pixmanager.shared.exception.BusinessException;
import br.com.pixmanager.shared.exception.IntegrationException;
import br.com.pixmanager.core.port.out.event.DomainEventPublisherPortOut;
import br.com.pixmanager.core.port.out.persistence.TransactionRunnerPortOut;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;

/**
 * Técnico × negócio decidido por tipo de exceção: {@link BusinessException} do DICT ⇒ REJECTED (definitivo, sem retry);
 * {@link IntegrationException} ⇒ continua PENDING_DICT e é retentada na próxima rodada (nunca vira REJECTED).
 */
public class ActivatePendingKeysService implements ActivatePendingKeysUseCase {

    private static final Logger log = LoggerFactory.getLogger(ActivatePendingKeysService.class);

    private final PixKeyPersistencePortOut persistence;
    private final DictGatewayPortOut dict;
    private final DomainEventPublisherPortOut events;
    private final TransactionRunnerPortOut transactionRunner;
    private final Clock clock;
    private final Duration minAge;

    public ActivatePendingKeysService(PixKeyPersistencePortOut persistence, DictGatewayPortOut dict,
                                      DomainEventPublisherPortOut events, TransactionRunnerPortOut transactionRunner, Clock clock,
                                      Duration minAge) {
        this.persistence = persistence;
        this.dict = dict;
        this.events = events;
        this.transactionRunner = transactionRunner;
        this.clock = clock;
        this.minAge = minAge;
    }

    @Override
    public int activatePending(int limit) {
        int activated = 0;
        for (PixKey key : persistence.findPendingDict(clock.instant().minus(minAge), limit)) {
            try {
                if (activate(key)) {
                    activated++;
                }
            } catch (RuntimeException exception) {
                // uma chave problemática não pode travar as demais (o job repete a cada poucos segundos)
                log.error("Falha inesperada ao ativar a chave {}; segue para a próxima", key.id(), exception);
            }
        }
        return activated;
    }

    /** Só a resposta do DICT decide ativar/rejeitar; falha ao GRAVAR localmente nunca vira REJECTED (a chave pode já estar no DICT). */
    private boolean activate(PixKey key) {
        DictRegistration registration;
        try {
            registration = dict.register(key);                                     // HTTP via gateway, fora de TX
        } catch (BusinessException exception) {
            log.info("DICT recusou a chave {} ({}): REJECTED", key.id(), exception.getCode());
            persistAndPublish(key.rejected(), "PixKeyRejected");
            return false;
        } catch (IntegrationException exception) {
            log.warn("DICT indisponível [{}] para a chave {}; tentará de novo: {}", exception.getCode(), key.id(), exception.getMessage());
            return false;
        }
        persistAndPublish(key.activated(registration.cid(), registration.registeredAt()), "PixKeyActivated");
        return true;
    }

    private void persistAndPublish(PixKey updated, String eventType) {
        transactionRunner.inTransaction(() -> {
            PixKey saved = persistence.save(updated);
            events.publish(new PixKeyEvent(eventType, saved));
        });
    }
}
