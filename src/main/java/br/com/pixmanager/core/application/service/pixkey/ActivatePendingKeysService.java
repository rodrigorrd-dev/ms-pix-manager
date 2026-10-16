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
    private final TransactionRunnerPortOut tx;
    private final Clock clock;
    private final Duration minAge;

    public ActivatePendingKeysService(PixKeyPersistencePortOut persistence, DictGatewayPortOut dict,
                                      DomainEventPublisherPortOut events, TransactionRunnerPortOut tx, Clock clock,
                                      Duration minAge) {
        this.persistence = persistence;
        this.dict = dict;
        this.events = events;
        this.tx = tx;
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
            } catch (RuntimeException e) {
                // uma chave problemática não pode travar as demais (o job repete a cada poucos segundos)
                log.error("Falha inesperada ao ativar a chave {}; segue para a próxima", key.id(), e);
            }
        }
        return activated;
    }

    /** Só a resposta do DICT decide ativar/rejeitar; falha ao GRAVAR localmente nunca vira REJECTED (a chave pode já estar no DICT). */
    private boolean activate(PixKey key) {
        DictRegistration registration;
        try {
            registration = dict.register(key);                                     // HTTP via gateway, fora de TX
        } catch (BusinessException e) {
            log.info("DICT recusou a chave {} ({}): REJECTED", key.id(), e.getCode());
            persistAndPublish(key.rejected(), "PixKeyRejected");
            return false;
        } catch (IntegrationException e) {
            log.warn("DICT indisponível [{}] para a chave {}; tentará de novo: {}", e.getCode(), key.id(), e.getMessage());
            return false;
        }
        persistAndPublish(key.activated(registration.cid(), registration.registeredAt()), "PixKeyActivated");
        return true;
    }

    private void persistAndPublish(PixKey updated, String eventType) {
        tx.inTransaction(() -> {
            PixKey saved = persistence.save(updated);
            events.publish(new PixKeyEvent(eventType, saved));
        });
    }
}
