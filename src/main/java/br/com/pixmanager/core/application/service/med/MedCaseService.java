package br.com.pixmanager.core.application.service.med;

import br.com.pixmanager.core.domain.entity.Caller;
import br.com.pixmanager.core.domain.entity.MedCase;
import br.com.pixmanager.core.domain.entity.MedContestation;
import br.com.pixmanager.core.domain.entity.Money;
import br.com.pixmanager.core.domain.entity.PaymentOrder;
import br.com.pixmanager.core.domain.entity.ReceivedPix;
import br.com.pixmanager.core.domain.entity.Refund;
import br.com.pixmanager.core.domain.entity.event.MedCaseEvent;
import br.com.pixmanager.core.domain.enums.LedgerPurpose;
import br.com.pixmanager.core.domain.enums.MedCaseType;
import br.com.pixmanager.core.domain.enums.MedRole;
import br.com.pixmanager.core.domain.enums.MedStatus;
import br.com.pixmanager.core.domain.enums.PaymentStatus;
import br.com.pixmanager.core.domain.enums.RefundNature;
import br.com.pixmanager.core.domain.enums.RefundStatus;
import br.com.pixmanager.core.port.in.ledger.ExecuteReservationUseCase;
import br.com.pixmanager.core.port.in.ledger.RequestReservationUseCase;
import br.com.pixmanager.core.port.in.med.FollowMedCasesUseCase;
import br.com.pixmanager.core.port.in.med.ManageMedCaseUseCase;
import br.com.pixmanager.core.port.in.refund.RequestRefundUseCase;
import br.com.pixmanager.core.port.in.refund.RequestRefundUseCase.RequestRefundCommand;
import br.com.pixmanager.core.port.out.MedGatewayPortOut;
import br.com.pixmanager.core.port.out.event.DomainEventPublisherPortOut;
import br.com.pixmanager.core.port.out.generator.IdGeneratorPortOut;
import br.com.pixmanager.core.port.out.persistence.MedCasePersistencePortOut;
import br.com.pixmanager.core.port.out.persistence.MedCasePersistencePortOut.HistoryEntry;
import br.com.pixmanager.core.port.out.persistence.PaymentOrderPersistencePortOut;
import br.com.pixmanager.core.port.out.persistence.ReceivedPixPersistencePortOut;
import br.com.pixmanager.core.port.out.persistence.RefundPersistencePortOut;
import br.com.pixmanager.core.port.out.persistence.TransactionRunnerPortOut;
import br.com.pixmanager.shared.constant.ErrorCodes;
import br.com.pixmanager.shared.exception.BadRequestException;
import br.com.pixmanager.shared.exception.BusinessException;
import br.com.pixmanager.shared.exception.ConflictException;
import br.com.pixmanager.shared.exception.NotFoundException;
import br.com.pixmanager.shared.model.dto.PageQuery;
import br.com.pixmanager.shared.model.dto.PageResult;
import br.com.pixmanager.shared.properties.PixPolicyProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * MED (BR-MED-01..08), nos dois papéis. HTTP (DICT, core) NUNCA dentro de transação: o gateway é chamado antes de gravar o caso; o
 * bloqueio e a devolução entram como reservas PENDING que o sweeper do ledger processa. Toda transição grava histórico append-only e
 * evento na outbox na MESMA transação (BR-MED-08).
 *
 * <p>Fora do escopo desta versão (docs/pendencias-funcionais.md): receber a notificação do DICT/SPI por Kafka, rastreamento automático
 * em camadas ({@code med_hop}) e bloqueio parcial conforme entra saldo — o caso nasce na camada informada e o bloqueio é um só.
 */
public class MedCaseService implements ManageMedCaseUseCase, FollowMedCasesUseCase {

    private static final Logger log = LoggerFactory.getLogger(MedCaseService.class);

    private final MedCasePersistencePortOut cases;
    private final ReceivedPixPersistencePortOut receivedPix;
    private final PaymentOrderPersistencePortOut orders;
    private final RefundPersistencePortOut refunds;
    private final MedGatewayPortOut gateway;
    private final RequestReservationUseCase reservations;
    private final ExecuteReservationUseCase ledger;
    private final RequestRefundUseCase refundUseCase;
    private final DomainEventPublisherPortOut events;
    private final IdGeneratorPortOut ids;
    private final TransactionRunnerPortOut tx;
    private final Clock clock;
    private final PixPolicyProperties.Med policy;

    public MedCaseService(MedCasePersistencePortOut cases, ReceivedPixPersistencePortOut receivedPix, PaymentOrderPersistencePortOut orders,
                          RefundPersistencePortOut refunds, MedGatewayPortOut gateway, RequestReservationUseCase reservations,
                          ExecuteReservationUseCase ledger, RequestRefundUseCase refundUseCase, DomainEventPublisherPortOut events,
                          IdGeneratorPortOut ids, TransactionRunnerPortOut tx, Clock clock, PixPolicyProperties.Med policy) {
        this.cases = cases;
        this.receivedPix = receivedPix;
        this.orders = orders;
        this.refunds = refunds;
        this.gateway = gateway;
        this.reservations = reservations;
        this.ledger = ledger;
        this.refundUseCase = refundUseCase;
        this.events = events;
        this.ids = ids;
        this.tx = tx;
        this.clock = clock;
        this.policy = policy;
    }

    // ---------------------------------------------------------------- abertura

    @Override
    public MedCase open(Caller caller, OpenMedCaseCommand c) {
        if (c.role() == null || c.caseType() == null || c.endToEndId() == null || c.description() == null || c.description().isBlank()) {
            throw new BadRequestException(ErrorCodes.INVALID_PAYLOAD, "Papel, tipo, endToEndId e descrição são obrigatórios.");
        }
        int layer = c.layer() == null ? 1 : c.layer();
        if (layer < 1) {
            throw new BadRequestException(ErrorCodes.INVALID_PAYLOAD, "A camada começa em 1.");
        }
        Instant now = clock.instant();
        UUID accountId;
        UUID receivedId = null;
        UUID orderId = null;
        Money tracked;
        Instant transactionAt;
        if (c.role() == MedRole.REPORTED) {
            ReceivedPix pix = receivedPix.findByEndToEndId(c.endToEndId()).filter(p -> caller.canAccess(p.receiverAccountId()))
                    .orElseThrow(() -> new NotFoundException(ErrorCodes.NOT_FOUND, "Pix recebido não encontrado."));
            accountId = pix.receiverAccountId();
            receivedId = pix.id();
            tracked = pix.amount();
            transactionAt = pix.settledAt();
        } else {
            PaymentOrder order = orders.findByEndToEndId(c.endToEndId()).filter(o -> caller.canAccess(o.payerAccountId()))
                    .orElseThrow(() -> new NotFoundException(ErrorCodes.NOT_FOUND, "Pagamento não encontrado."));
            if (order.status() != PaymentStatus.SETTLED) {
                throw new BusinessException(ErrorCodes.BUSINESS_RULE, "Só um pagamento liquidado pode ser reportado ao MED.");
            }
            accountId = order.payerAccountId();
            orderId = order.id();
            tracked = order.amount();
            transactionAt = order.settledAt();
        }
        if (now.isAfter(transactionAt.plus(policy.openWindow()))) {
            throw new BusinessException(ErrorCodes.MED_WINDOW_EXPIRED, "Prazo para abrir o caso MED (" + policy.openWindow().toDays() + " dias) excedido.");
        }
        if (cases.existsLive(c.endToEndId(), c.role(), layer)) {
            throw new ConflictException(ErrorCodes.CONFLICT, "Já existe um caso MED em andamento para este Pix nesta camada.");
        }
        String fundsRecoveryId = c.role() == MedRole.REPORTER ? gateway.report(c.endToEndId(), c.caseType(), c.description(), layer) : null;   // HTTP fora de TX
        MedCase created = MedCase.open(ids.newId(), fundsRecoveryId, c.endToEndId(), receivedId, orderId, accountId, c.caseType(), c.role(), layer,
                c.description(), now, now.plus(policy.analysis()), tracked);
        tx.inTransaction(() -> {
            cases.insert(created);
            cases.appendHistory(created.id(), null, MedStatus.OPEN, caller.clientId(), "Caso aberto", now);
            events.publish(new MedCaseEvent("OPENED", created));
        });
        log.info("Caso MED aberto: id={} e2eId={} papel={} camada={}", created.id(), c.endToEndId(), c.role(), layer);
        return created;
    }

    @Override
    public MedCase get(Caller caller, UUID id) {
        return load(caller, id);
    }

    @Override
    public PageResult<MedCase> list(Caller caller, MedStatus status, PageQuery page) {
        return cases.list(caller.accounts(), caller.allAccounts(), status, page);
    }

    @Override
    public List<HistoryEntry> history(Caller caller, UUID id) {
        load(caller, id);
        return cases.history(id);
    }

    // ---------------------------------------------------------------- decisões

    @Override
    public MedCase accept(Caller caller, UUID id, String note) {
        MedCase current = requireRole(load(caller, id), MedRole.REPORTED);
        MedCase accepted = current.accepted();
        return tx.inTransaction(() -> {
            MedCase saved = cases.save(accepted);
            reservations.request(blockKey(id), saved.accountId(), LedgerPurpose.MED_BLOCK, "MED_CASE", id, saved.trackedAmount());
            transition(current, saved, caller, note == null ? "Aceito; bloqueio cautelar solicitado" : note, "ACCEPTED");
            return saved;
        });
    }

    @Override
    public MedCase refuse(Caller caller, UUID id, String note) {
        MedCase current = requireRole(load(caller, id), MedRole.REPORTED);
        return tx.inTransaction(() -> {
            MedCase saved = cases.save(current.refused());
            transition(current, saved, caller, note, "REFUSED");
            return saved;
        });
    }

    @Override
    public MedCase cancel(Caller caller, UUID id, String note) {
        MedCase current = load(caller, id);
        MedCase cancelled = current.cancelled();                                    // valida a transição antes de qualquer I/O
        if (current.role() == MedRole.REPORTER && current.dictFundsRecoveryId() != null) {
            gateway.cancel(current.dictFundsRecoveryId());                          // HTTP fora de TX
        }
        boolean hadBlock = current.blockedAmount() != null && current.blockedAmount().isPositive();
        if (current.role() == MedRole.REPORTED && current.status() == MedStatus.ACCEPTED) {
            ledger.cancel(blockKey(id));                                            // libera o valor no core (idempotente)
        }
        return tx.inTransaction(() -> {
            MedCase saved = cases.save(hadBlock ? cancelled.released() : cancelled);
            if (hadBlock) {
                cases.releaseBlock(id);
            }
            transition(current, saved, caller, note, "CANCELLED");
            return saved;
        });
    }

    @Override
    public MedCase startRefund(Caller caller, UUID id) {
        MedCase current = requireRole(load(caller, id), MedRole.REPORTED);
        if (current.status() != MedStatus.ACCEPTED || current.blockedAmount() == null || !current.blockedAmount().isPositive()) {
            throw new BusinessException(ErrorCodes.BUSINESS_RULE, "A devolução MED exige o caso aceito e o valor bloqueado.");
        }
        ReceivedPix pix = receivedPix.findByEndToEndId(current.endToEndId()).orElseThrow();
        if (pix.refundableAmount().value().subtract(pix.refundedTotal().value()).compareTo(current.blockedAmount().value()) < 0) {
            throw new BusinessException(ErrorCodes.REFUND_EXCEEDS_AMOUNT, "O valor bloqueado excede o saldo devolvível deste Pix.");
        }
        ledger.cancel(blockKey(id));          // libera o bloqueio ANTES: a devolução debita a mesma conta (HTTP fora de TX, idempotente)
        Money amount = current.blockedAmount();
        RefundNature nature = current.caseType() == MedCaseType.FRAUD ? RefundNature.MED_FRAUDE : RefundNature.MED_OPERACIONAL;
        return tx.inTransaction(() -> {
            Refund refund = switch (refundUseCase.requestMed(Caller.system("med"), new RequestRefundCommand(current.endToEndId(),
                    "med" + id.toString().replace("-", ""), amount, nature, "MED " + current.caseType()))) {
                case RequestRefundUseCase.RequestRefundResult.Created created -> created.refund();
                case RequestRefundUseCase.RequestRefundResult.Replayed replayed -> replayed.refund();
            };
            cases.releaseBlock(id);
            MedCase saved = cases.save(current.refunding(refund.id()));
            transition(current, saved, caller, "Devolução MED solicitada", "REFUND_REQUESTED");
            return saved;
        });
    }

    @Override
    public MedCase close(Caller caller, UUID id, String note) {
        MedCase current = requireRole(load(caller, id), MedRole.REPORTER);
        return tx.inTransaction(() -> {
            MedCase saved = cases.save(current.closed());
            transition(current, saved, caller, note, "CLOSED");
            return saved;
        });
    }

    // ---------------------------------------------------------------- contestação

    @Override
    public MedContestation contest(Caller caller, UUID caseId, String reason) {
        MedCase current = requireRole(load(caller, caseId), MedRole.REPORTED);
        if (current.status() != MedStatus.CLOSED || current.refundId() == null) {
            throw new BusinessException(ErrorCodes.BUSINESS_RULE, "Só se contesta a devolução de um caso encerrado com devolução.");
        }
        if (reason == null || reason.isBlank()) {
            throw new BadRequestException(ErrorCodes.INVALID_PAYLOAD, "O motivo da contestação é obrigatório.");
        }
        Refund refund = refunds.findById(current.refundId()).orElseThrow();
        Instant now = clock.instant();
        Instant deadline = refund.settledAt().plus(policy.contestationWindow());
        if (now.isAfter(deadline)) {
            throw new BusinessException(ErrorCodes.MED_CONTESTATION_EXPIRED, "Prazo de contestação (" + policy.contestationWindow().toDays() + " dias da devolução) excedido.");
        }
        MedContestation contestation = new MedContestation(ids.newId(), caseId, refund.id(), "OPEN", reason, refund.settledAt(), deadline, null, 0);
        return tx.inTransaction(() -> {
            cases.insertContestation(contestation);
            cases.appendHistory(caseId, current.status(), current.status(), caller.clientId(), "Contestação aberta", now);
            events.publish(new MedCaseEvent("CONTESTATION_OPENED", current, null, deadline));
            return contestation;
        });
    }

    @Override
    public MedContestation decideContestation(Caller caller, UUID contestationId, boolean accepted, String note) {
        MedContestation contestation = cases.findContestation(contestationId).orElseThrow(() -> new NotFoundException(ErrorCodes.NOT_FOUND, "Contestação não encontrada."));
        MedCase current = load(caller, contestation.caseId());
        if (!"OPEN".equals(contestation.status())) {
            throw new BusinessException(ErrorCodes.BUSINESS_RULE, "A contestação já foi decidida.");
        }
        Instant now = clock.instant();
        return tx.inTransaction(() -> {
            MedContestation decided = cases.saveContestation(new MedContestation(contestation.id(), contestation.caseId(), contestation.refundId(),
                    accepted ? "ACCEPTED" : "REJECTED", contestation.reason(), contestation.refundedAt(), contestation.deadlineAt(), now, contestation.version()));
            MedCase saved = accepted ? cases.save(current.contested()) : current;
            cases.appendHistory(current.id(), current.status(), saved.status(), caller.clientId(), note == null ? "Contestação " + decided.status() : note, now);
            events.publish(new MedCaseEvent("CONTESTATION_DECIDED", saved));
            return decided;
        });
    }

    // ---------------------------------------------------------------- acompanhamento

    @Override
    public int runCycle() {
        int moved = 0;
        for (MedCase c : cases.findRefunding(50)) {
            try {
                Refund refund = refunds.findById(c.refundId()).orElseThrow();
                if (refund.status() == RefundStatus.DEVOLVIDO) {
                    tx.inTransaction(() -> {
                        MedCase saved = cases.save(c.closed());
                        cases.appendHistory(c.id(), c.status(), saved.status(), "system", "Devolução liquidada", clock.instant());
                        events.publish(new MedCaseEvent("REFUND_SETTLED", saved, refund.amount(), saved.reportedAt().plus(policy.contestationWindow())));
                    });
                    moved++;
                } else if (refund.status() == RefundStatus.NAO_REALIZADO) {
                    tx.inTransaction(() -> {
                        MedCase saved = cases.save(c.refundFailed());
                        cases.appendHistory(c.id(), c.status(), saved.status(), "system", "Devolução não realizada: " + refund.statusReason(), clock.instant());
                        events.publish(new MedCaseEvent("REFUND_FAILED", saved));
                    });
                    moved++;
                }
            } catch (RuntimeException e) {
                log.error("Erro ao acompanhar o caso MED {}", c.id(), e);
            }
        }
        return moved;
    }

    // ---------------------------------------------------------------- apoio

    static String blockKey(UUID caseId) {
        return "med-" + caseId + "-1";
    }

    private MedCase load(Caller caller, UUID id) {
        return cases.findById(id).filter(c -> caller.canAccess(c.accountId()))
                .orElseThrow(() -> new NotFoundException(ErrorCodes.NOT_FOUND, "Caso MED não encontrado."));
    }

    private static MedCase requireRole(MedCase c, MedRole role) {
        if (c.role() != role) {
            throw new BusinessException(ErrorCodes.BUSINESS_RULE, "Esta operação é do papel " + role + "; o caso é " + c.role() + ".");
        }
        return c;
    }

    private void transition(MedCase from, MedCase to, Caller caller, String note, String action) {
        cases.appendHistory(to.id(), from.status(), to.status(), caller.clientId(), note, clock.instant());
        events.publish(new MedCaseEvent(action, to));
    }
}
