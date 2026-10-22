package br.com.pixmanager.core.application.service.refund;

import br.com.pixmanager.core.domain.entity.Caller;
import br.com.pixmanager.core.domain.entity.EndToEndId;
import br.com.pixmanager.core.domain.entity.ReceivedPix;
import br.com.pixmanager.core.domain.entity.Refund;
import br.com.pixmanager.core.domain.entity.RefundPolicy;
import br.com.pixmanager.core.domain.entity.event.RefundEvent;
import br.com.pixmanager.core.domain.enums.LedgerPurpose;
import br.com.pixmanager.core.port.in.ledger.RequestReservationUseCase;
import br.com.pixmanager.core.port.in.refund.RequestRefundUseCase;
import br.com.pixmanager.core.port.out.event.DomainEventPublisherPortOut;
import br.com.pixmanager.core.port.out.generator.EndToEndIdGeneratorPortOut;
import br.com.pixmanager.core.port.out.generator.IdGeneratorPortOut;
import br.com.pixmanager.core.port.out.persistence.ReceivedPixPersistencePortOut;
import br.com.pixmanager.core.port.out.persistence.RefundPersistencePortOut;
import br.com.pixmanager.shared.constant.ErrorCodes;
import br.com.pixmanager.shared.exception.BusinessException;
import br.com.pixmanager.shared.exception.NotFoundException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

/**
 * BR-REF-01..07, tudo numa transação: idempotência por {@code (e2eId, id)}; prazo; teto devolvível ATÔMICO (UPDATE
 * condicional, não read→decide→write); reserva PENDING no core com o {@code rtrId} como chave de idempotência; evento
 * {@code RefundRequested}. A chamada ao core acontece DEPOIS, pelo sweeper — nunca dentro desta transação.
 */
public class RequestRefundService implements RequestRefundUseCase {

    private static final Logger log = LoggerFactory.getLogger(RequestRefundService.class);

    private final ReceivedPixPersistencePortOut receivedPix;
    private final RefundPersistencePortOut refunds;
    private final RequestReservationUseCase reservations;
    private final DomainEventPublisherPortOut events;
    private final EndToEndIdGeneratorPortOut e2eIds;
    private final IdGeneratorPortOut ids;
    private final Clock clock;
    private final Duration refundWindow;

    public RequestRefundService(ReceivedPixPersistencePortOut receivedPix, RefundPersistencePortOut refunds,
                                RequestReservationUseCase reservations, DomainEventPublisherPortOut events,
                                EndToEndIdGeneratorPortOut e2eIds, IdGeneratorPortOut ids, Clock clock, Duration refundWindow) {
        this.receivedPix = receivedPix;
        this.refunds = refunds;
        this.reservations = reservations;
        this.events = events;
        this.e2eIds = e2eIds;
        this.ids = ids;
        this.clock = clock;
        this.refundWindow = refundWindow;
    }

    @Override
    public RequestRefundResult request(Caller caller, RequestRefundCommand cmd) {
        RefundPolicy.assertNatureAllowedForClient(cmd.nature());
        return doRequest(caller, cmd);
    }

    @Override
    public RequestRefundResult requestMed(Caller caller, RequestRefundCommand cmd) {
        if (cmd.nature() == null || !cmd.nature().isMed()) {
            throw new BusinessException(ErrorCodes.BUSINESS_RULE, "O fluxo MED exige uma natureza MED.");
        }
        return doRequest(caller, cmd);
    }

    private RequestRefundResult doRequest(Caller caller, RequestRefundCommand cmd) {
        EndToEndId e2e = new EndToEndId(cmd.endToEndId());
        ReceivedPix pix = receivedPix.findByEndToEndId(e2e.value())
                .filter(foundPix -> caller.canAccess(foundPix.receiverAccountId()))           // fora do escopo ⇒ 404
                .orElseThrow(() -> new NotFoundException(ErrorCodes.NOT_FOUND, "Pix não encontrado."));

        Optional<Refund> existing = refunds.findByEndToEndIdAndClientId(e2e.value(), cmd.clientRefundId());
        if (existing.isPresent()) {
            if (existing.get().sameRequestAs(cmd.amount(), cmd.nature(), cmd.description())) {
                return new RequestRefundResult.Replayed(existing.get());
            }
            throw new BusinessException(ErrorCodes.IDEMPOTENCY_MISMATCH, "O id da devolução já foi usado com outro conteúdo.");
        }
        RefundPolicy.assertWithinWindow(pix.settledAt(), clock.instant(), refundWindow);

        Refund refund = Refund.request(ids.newId(), cmd.clientRefundId(), pix.id(), e2e, e2eIds.nextReturnId(), cmd.amount(),
                cmd.nature(), cmd.description(), clock.instant());
        if (!receivedPix.reserveRefundable(pix.id(), refund.amount())) {
            throw new BusinessException(ErrorCodes.REFUND_EXCEEDS_AMOUNT, "O valor excede o saldo devolvível deste Pix.");
        }
        Refund saved = refunds.save(refund);
        reservations.request(saved.rtrId(), pix.receiverAccountId(), LedgerPurpose.REFUND, "REFUND", saved.id(), saved.amount());
        events.publish(new RefundEvent("RefundRequested", saved));
        log.info("Devolução solicitada: id={} e2eId={} rtrId={}", saved.id(), e2e.value(), saved.rtrId());
        return new RequestRefundResult.Created(saved);
    }
}
