package br.com.pixmanager.core.application.service.automatic;

import br.com.pixmanager.core.domain.entity.AccountInfo;
import br.com.pixmanager.core.domain.entity.AutomaticAttempt;
import br.com.pixmanager.core.domain.entity.AutomaticAuthorization;
import br.com.pixmanager.core.domain.entity.AutomaticCharge;
import br.com.pixmanager.core.domain.entity.Caller;
import br.com.pixmanager.core.domain.entity.Money;
import br.com.pixmanager.core.domain.entity.event.AutomaticAuthorizationEvent;
import br.com.pixmanager.core.port.in.account.FindAccountUseCase;
import br.com.pixmanager.core.port.in.automatic.ManageAutomaticUseCase;
import br.com.pixmanager.core.port.out.AutomaticGatewayPortOut;
import br.com.pixmanager.core.port.out.event.DomainEventPublisherPortOut;
import br.com.pixmanager.core.port.out.generator.EndToEndIdGeneratorPortOut;
import br.com.pixmanager.core.port.out.generator.IdGeneratorPortOut;
import br.com.pixmanager.core.port.out.persistence.AutomaticPersistencePortOut;
import br.com.pixmanager.core.port.out.persistence.TransactionRunnerPortOut;
import br.com.pixmanager.shared.constant.ErrorCodes;
import br.com.pixmanager.shared.exception.BadRequestException;
import br.com.pixmanager.shared.exception.BusinessException;
import br.com.pixmanager.shared.exception.NotFoundException;
import br.com.pixmanager.shared.model.document.Document;
import br.com.pixmanager.shared.model.dto.PageQuery;
import br.com.pixmanager.shared.model.dto.PageResult;
import br.com.pixmanager.shared.properties.AutomaticProperties;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Pix Automático, lado pagador (BR-AUT-01..05, 08). O gateway (HTTP) é chamado DEPOIS de gravar a decisão e FORA da transação; se ele
 * falhar, a decisão fica gravada e a exceção sobe (o recebedor pode ser reavisado repetindo a operação, que é idempotente no gateway).
 * Fora do escopo: receber a solicitação/cobrança do SPI por Kafka e os prazos exatos do regulamento (parametrizados, não confirmados).
 */
public class ManageAutomaticService implements ManageAutomaticUseCase {

    private static final Pattern TXID = Pattern.compile("^[a-zA-Z0-9]{26,35}$");

    private final AutomaticPersistencePortOut persistence;
    private final FindAccountUseCase accounts;
    private final AutomaticGatewayPortOut gateway;
    private final DomainEventPublisherPortOut events;
    private final IdGeneratorPortOut ids;
    private final EndToEndIdGeneratorPortOut endToEndIdGenerator;
    private final TransactionRunnerPortOut transactionRunner;
    private final Clock clock;
    private final AutomaticProperties properties;

    public ManageAutomaticService(AutomaticPersistencePortOut persistence, FindAccountUseCase accounts, AutomaticGatewayPortOut gateway,
                                  DomainEventPublisherPortOut events, IdGeneratorPortOut ids, EndToEndIdGeneratorPortOut endToEndIdGenerator,
                                  TransactionRunnerPortOut transactionRunner, Clock clock, AutomaticProperties properties) {
        this.persistence = persistence;
        this.accounts = accounts;
        this.gateway = gateway;
        this.events = events;
        this.ids = ids;
        this.endToEndIdGenerator = endToEndIdGenerator;
        this.transactionRunner = transactionRunner;
        this.clock = clock;
        this.properties = properties;
    }

    @Override
    public AutomaticAuthorization receiveAuthorization(Caller caller, ReceiveAuthorizationCommand command) {
        if (!caller.canAccess(command.payerAccountId())) {
            throw new NotFoundException(ErrorCodes.NOT_FOUND, "Conta não encontrada.");
        }
        AccountInfo payer = accounts.findById(command.payerAccountId()).orElseThrow(() -> new NotFoundException(ErrorCodes.NOT_FOUND, "Conta não encontrada."));
        if (!payer.isActive()) {
            throw new BusinessException(ErrorCodes.BUSINESS_RULE, "A conta do pagador não está ativa.");
        }
        if (command.payerDocument() == null || !Document.of(command.payerDocument()).value().equals(payer.document().value())) {
            throw new BusinessException(ErrorCodes.DESTINATION_MISMATCH, "O documento do pagador da solicitação não é o do titular da conta.");   // BR-AUT-02
        }
        Document.of(command.receiverDocument());                                                         // dígito verificador do recebedor
        Instant now = clock.instant();
        AutomaticAuthorization created = AutomaticAuthorization.received(ids.newId(), command.idRec(), payer.id(), Document.of(command.receiverDocument()).value(),
                command.receiverName(), command.contract(), command.objectDescription(), command.periodicity(), command.startDate(), command.endDate(), command.fixedAmount(), command.receiverMinAmount(),
                now.plus(properties.requestValidity()));
        transactionRunner.inTransaction(() -> {
            persistence.insertAuthorization(created);
            events.publish(new AutomaticAuthorizationEvent("AutomaticAuthorizationReceived", created));
        });
        return created;
    }

    @Override
    public AutomaticAuthorization getAuthorization(Caller caller, UUID id) {
        return load(caller, id);
    }

    @Override
    public PageResult<AutomaticAuthorization> listAuthorizations(Caller caller, String status, PageQuery page) {
        return persistence.listAuthorizations(caller.accounts(), caller.allAccounts(), status, page);
    }

    @Override
    public AutomaticAuthorization approve(Caller caller, UUID id, Money payerMaxAmount) {
        AutomaticAuthorization current = load(caller, id);
        AutomaticAuthorization approved = current.approved(payerMaxAmount, clock.instant());
        AutomaticAuthorization saved = decide(approved, "AutomaticAuthorizationApproved");
        gateway.approve(saved.idRec(), saved.payerMaxAmount());                                    // HTTP fora de TX, depois de gravar
        return saved;
    }

    @Override
    public AutomaticAuthorization reject(Caller caller, UUID id, String rejectionCode) {
        AutomaticAuthorization saved = decide(load(caller, id).rejected(rejectionCode), "AutomaticAuthorizationRejected");
        gateway.reject(saved.idRec(), saved.rejectionCode());
        return saved;
    }

    @Override
    public AutomaticAuthorization cancel(Caller caller, UUID id, String cancelledBy) {
        AutomaticAuthorization current = load(caller, id);
        AutomaticAuthorization cancelled = current.cancelled(cancelledBy, clock.instant());
        AutomaticAuthorization saved = transactionRunner.inTransaction(() -> {
            AutomaticAuthorization authorization = persistence.saveAuthorization(cancelled);
            persistence.cancelLiveCharges(id);
            events.publish(new AutomaticAuthorizationEvent("AutomaticAuthorizationCancelled", authorization));
            return authorization;
        });
        if (!"RECEIVER".equals(cancelledBy)) {                                                     // o recebedor já sabe quando foi ele quem cancelou
            gateway.cancel(saved.idRec(), cancelledBy);
        }
        return saved;
    }

    @Override
    public AutomaticAuthorization adjustLimit(Caller caller, UUID id, Money payerMaxAmount) {
        return decide(load(caller, id).withPayerMax(payerMaxAmount), "AutomaticAuthorizationLimitAdjusted");
    }

    @Override
    public AutomaticCharge registerCharge(Caller caller, UUID authorizationId, RegisterChargeCommand command) {
        AutomaticAuthorization authorization = load(caller, authorizationId);
        if (!authorization.isApproved()) {
            throw new BusinessException(ErrorCodes.AUTOMATIC_NOT_AUTHORIZED, "Cobrança recusada: a autorização não está aprovada (BR-AUT-08).");
        }
        if (command.txid() == null || !TXID.matcher(command.txid()).matches()) {
            throw new BadRequestException(ErrorCodes.INVALID_PAYLOAD, "txid inválido: 26 a 35 caracteres alfanuméricos.");
        }
        if (command.dueDate() == null || command.amount() == null || !command.amount().isPositive() || command.receiverIspb() == null || !command.receiverIspb().matches("\\d{8}")
                || command.receiverBranch() == null || command.receiverAccount() == null || command.receiverAccountType() == null) {
            throw new BadRequestException(ErrorCodes.INVALID_PAYLOAD, "A cobrança exige vencimento, valor e a conta do recebedor.");
        }
        LocalDate today = today();
        long notice = java.time.temporal.ChronoUnit.DAYS.between(today, command.dueDate());
        if (notice < properties.minNoticeDays() || notice > properties.maxNoticeDays()) {
            throw new BusinessException(ErrorCodes.BUSINESS_RULE, "O vencimento deve estar entre " + properties.minNoticeDays() + " e " + properties.maxNoticeDays()
                    + " dias à frente.");
        }
        if (command.dueDate().isBefore(authorization.startDate()) || authorization.endDate() != null && command.dueDate().isAfter(authorization.endDate())) {
            throw new BusinessException(ErrorCodes.BUSINESS_RULE, "O vencimento está fora da vigência da autorização.");
        }
        if (authorization.fixedAmount() != null && !authorization.fixedAmount().equals(command.amount())
                || authorization.fixedAmount() == null && authorization.receiverMinAmount() != null && command.amount().compareTo(authorization.receiverMinAmount()) < 0
                || authorization.payerMaxAmount() != null && command.amount().compareTo(authorization.payerMaxAmount()) > 0) {
            throw new BusinessException(ErrorCodes.BUSINESS_RULE, "O valor da cobrança não respeita o valor fixo, o mínimo ou o teto do pagador.");
        }
        AutomaticCharge charge = new AutomaticCharge(ids.newId(), authorization.id(), command.txid(), command.dueDate(), command.amount(), command.receiverIspb(), command.receiverBranch(),
                command.receiverAccount(), command.receiverAccountType(), "ATIVA", 0);
        AutomaticAttempt first = new AutomaticAttempt(ids.newId(), charge.id(), "AGND", "AGENDADA", endToEndIdGenerator.nextEndToEndId(), command.dueDate(), null, null, 0);
        transactionRunner.inTransaction(() -> persistence.insertCharge(charge, first));
        return charge;
    }

    @Override
    public List<AutomaticCharge> charges(Caller caller, UUID authorizationId) {
        load(caller, authorizationId);
        return persistence.chargesOf(authorizationId);
    }

    @Override
    public List<AutomaticAttempt> attempts(Caller caller, UUID authorizationId, UUID chargeId) {
        loadCharge(caller, authorizationId, chargeId);
        return persistence.attemptsOf(chargeId);
    }

    @Override
    public AutomaticCharge cancelCharge(Caller caller, UUID authorizationId, UUID chargeId) {
        AutomaticCharge charge = loadCharge(caller, authorizationId, chargeId);
        if (!charge.isLive()) {
            throw new BusinessException(ErrorCodes.BUSINESS_RULE, "Cobrança " + charge.status() + " não pode ser cancelada.");
        }
        ZonedDateTime limit = charge.dueDate().atStartOfDay(properties.zone());                    // 23h59 da véspera = antes das 00h do vencimento
        if (!clock.instant().isBefore(limit.toInstant())) {
            throw new BusinessException(ErrorCodes.BUSINESS_RULE, "O pagador pode cancelar a cobrança até as 23h59 da véspera do vencimento.");
        }
        return transactionRunner.inTransaction(() -> cancelChargeRows(charge));
    }

    // ---------------------------------------------------------------- apoio

    private AutomaticCharge cancelChargeRows(AutomaticCharge charge) {
        for (AutomaticAttempt attempt : persistence.attemptsOf(charge.id())) {
            if ("AGENDADA".equals(attempt.status())) {
                persistence.saveAttempt(attempt.with("CANCELADA", null));
            }
        }
        return persistence.saveCharge(charge.with("CANCELADA"));
    }

    private AutomaticAuthorization decide(AutomaticAuthorization next, String eventType) {
        return transactionRunner.inTransaction(() -> {
            AutomaticAuthorization saved = persistence.saveAuthorization(next);
            events.publish(new AutomaticAuthorizationEvent(eventType, saved));
            return saved;
        });
    }

    private AutomaticAuthorization load(Caller caller, UUID id) {
        return persistence.findAuthorization(id).filter(authorization -> caller.canAccess(authorization.payerAccountId()))
                .orElseThrow(() -> new NotFoundException(ErrorCodes.NOT_FOUND, "Autorização não encontrada."));
    }

    private AutomaticCharge loadCharge(Caller caller, UUID authorizationId, UUID chargeId) {
        load(caller, authorizationId);
        return persistence.findCharge(chargeId).filter(charge -> charge.authorizationId().equals(authorizationId))
                .orElseThrow(() -> new NotFoundException(ErrorCodes.NOT_FOUND, "Cobrança não encontrada."));
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(properties.zone()));
    }

    static Set<String> finalAttemptStatuses() {
        return Set.of("PAGA", "REJEITADA", "CANCELADA", "EXPIRADA");
    }
}
