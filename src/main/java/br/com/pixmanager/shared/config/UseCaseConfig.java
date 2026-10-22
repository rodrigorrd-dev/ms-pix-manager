package br.com.pixmanager.shared.config;

import br.com.pixmanager.core.application.service.automatic.ExecuteAutomaticService;
import br.com.pixmanager.core.application.service.automatic.ManageAutomaticService;
import br.com.pixmanager.core.port.in.automatic.ExecuteAutomaticUseCase;
import br.com.pixmanager.core.port.in.automatic.ManageAutomaticUseCase;
import br.com.pixmanager.core.port.out.AutomaticGatewayPortOut;
import br.com.pixmanager.core.port.out.persistence.AutomaticPersistencePortOut;
import br.com.pixmanager.shared.properties.AutomaticProperties;
import br.com.pixmanager.core.application.service.med.MedBlockReservationHandler;
import br.com.pixmanager.core.application.service.med.MedCaseService;
import br.com.pixmanager.core.port.out.MedGatewayPortOut;
import br.com.pixmanager.core.port.out.persistence.MedCasePersistencePortOut;
import br.com.pixmanager.core.application.service.schedule.ExecuteScheduledPaymentsService;
import br.com.pixmanager.core.application.service.schedule.ManageScheduledPaymentService;
import br.com.pixmanager.core.domain.entity.ScheduleRetryPolicy;
import br.com.pixmanager.core.port.in.schedule.ExecuteScheduledPaymentsUseCase;
import br.com.pixmanager.core.port.in.schedule.ManageScheduledPaymentUseCase;
import br.com.pixmanager.core.port.out.persistence.ScheduledPaymentPersistencePortOut;
import br.com.pixmanager.shared.properties.ScheduleProperties;
import br.com.pixmanager.core.application.service.limit.ConsumeLimitService;
import br.com.pixmanager.core.application.service.limit.ManageLimitService;
import br.com.pixmanager.core.domain.entity.LimitPolicy;
import br.com.pixmanager.core.domain.entity.Money;
import br.com.pixmanager.core.port.in.limit.ConsumeLimitUseCase;
import br.com.pixmanager.core.port.in.limit.ManageLimitUseCase;
import br.com.pixmanager.core.port.out.persistence.LimitPersistencePortOut;
import br.com.pixmanager.core.port.out.persistence.LimitUsagePortOut;
import br.com.pixmanager.core.application.service.payment.ApplyPaymentStatusService;
import br.com.pixmanager.core.application.service.payment.CancelPaymentOrderService;
import br.com.pixmanager.core.application.service.payment.CreatePaymentOrderService;
import br.com.pixmanager.core.application.service.payment.DecodeBrCodeService;
import br.com.pixmanager.core.application.service.payment.FindPaymentOrderService;
import br.com.pixmanager.core.application.service.payment.PaymentReservationHandler;
import br.com.pixmanager.core.port.in.payment.ApplyPaymentStatusUseCase;
import br.com.pixmanager.core.port.in.payment.CancelPaymentOrderUseCase;
import br.com.pixmanager.core.port.in.payment.CreatePaymentOrderUseCase;
import br.com.pixmanager.core.port.in.payment.DecodeBrCodeUseCase;
import br.com.pixmanager.core.port.in.payment.FindPaymentOrderUseCase;
import br.com.pixmanager.core.port.out.persistence.PaymentOrderPersistencePortOut;
import br.com.pixmanager.core.port.out.persistence.TrustedDevicePortOut;
import br.com.pixmanager.core.application.service.account.FindAccountService;
import br.com.pixmanager.core.application.service.charge.CreateChargeService;
import br.com.pixmanager.core.application.service.charge.FindChargeService;
import br.com.pixmanager.core.application.service.charge.ReviseChargeService;
import br.com.pixmanager.core.application.service.charge.SettleChargeService;
import br.com.pixmanager.core.application.service.ledger.ExecuteReservationService;
import br.com.pixmanager.core.application.service.ledger.RequestReservationService;
import br.com.pixmanager.core.application.service.ledger.ProcessReservationService;
import br.com.pixmanager.core.application.service.receivedpix.FindReceivedPixService;
import br.com.pixmanager.core.application.service.receivedpix.ReceivePixService;
import br.com.pixmanager.core.application.service.refund.FindRefundService;
import br.com.pixmanager.core.application.service.refund.RefundReservationHandler;
import br.com.pixmanager.core.application.service.refund.RequestRefundService;
import br.com.pixmanager.core.application.service.refund.SettleRefundService;
import br.com.pixmanager.core.port.in.ledger.ProcessReservationUseCase;
import br.com.pixmanager.core.port.in.ledger.ReservationOutcomeHandler;
import br.com.pixmanager.core.port.in.receivedpix.FindReceivedPixUseCase;
import br.com.pixmanager.core.port.in.receivedpix.ReceivePixUseCase;
import br.com.pixmanager.core.port.in.refund.FindRefundUseCase;
import br.com.pixmanager.core.port.in.refund.RequestRefundUseCase;
import br.com.pixmanager.core.port.in.refund.SettleRefundUseCase;
import br.com.pixmanager.core.port.out.event.InboxPortOut;
import br.com.pixmanager.core.port.out.generator.EndToEndIdGeneratorPortOut;
import br.com.pixmanager.core.port.out.persistence.ReceivedPixPersistencePortOut;
import br.com.pixmanager.core.port.out.persistence.RefundPersistencePortOut;
import br.com.pixmanager.core.application.service.pixkey.ActivatePendingKeysService;
import br.com.pixmanager.core.application.service.pixkey.FindActiveKeyService;
import br.com.pixmanager.core.application.service.pixkey.LookupExternalKeyService;
import br.com.pixmanager.core.application.service.pixkey.ManagePixKeyService;
import br.com.pixmanager.core.application.service.pixkey.RegisterPixKeyService;
import br.com.pixmanager.core.port.in.account.FindAccountUseCase;
import br.com.pixmanager.core.port.in.charge.CreateChargeUseCase;
import br.com.pixmanager.core.port.in.charge.FindChargeUseCase;
import br.com.pixmanager.core.port.in.charge.ReviseChargeUseCase;
import br.com.pixmanager.core.port.in.charge.SettleChargeUseCase;
import br.com.pixmanager.core.port.in.ledger.ExecuteReservationUseCase;
import br.com.pixmanager.core.port.in.ledger.RequestReservationUseCase;
import br.com.pixmanager.core.port.in.pixkey.ActivatePendingKeysUseCase;
import br.com.pixmanager.core.port.in.pixkey.FindActiveKeyUseCase;
import br.com.pixmanager.core.port.in.pixkey.LookupExternalKeyUseCase;
import br.com.pixmanager.core.port.in.pixkey.ManagePixKeyUseCase;
import br.com.pixmanager.core.application.service.webhook.ManageWebhookService;
import br.com.pixmanager.core.application.service.webhook.WebhookDispatchService;
import br.com.pixmanager.core.application.service.webhook.WebhookNotificationService;
import br.com.pixmanager.core.domain.entity.WebhookRetryPolicy;
import br.com.pixmanager.core.port.in.webhook.DispatchWebhooksUseCase;
import br.com.pixmanager.core.port.in.webhook.ManageWebhookUseCase;
import br.com.pixmanager.core.port.in.webhook.NotifyPixReceivedUseCase;
import br.com.pixmanager.core.port.out.WebhookSenderPortOut;
import br.com.pixmanager.core.port.out.WebhookUrlPolicyPortOut;
import br.com.pixmanager.core.port.out.persistence.WebhookDeliveryPortOut;
import br.com.pixmanager.core.port.out.persistence.WebhookSubscriptionPortOut;
import br.com.pixmanager.core.port.out.persistence.WebhookTargetPortOut;
import br.com.pixmanager.core.port.in.pixkey.OwnershipProofUseCase;
import br.com.pixmanager.core.application.service.pixkey.OwnershipProofService;
import br.com.pixmanager.core.port.out.OtpCodePortOut;
import br.com.pixmanager.core.port.out.OtpSenderPortOut;
import br.com.pixmanager.core.port.out.persistence.OtpChallengePortOut;
import br.com.pixmanager.core.port.in.pixkey.RegisterPixKeyUseCase;
import br.com.pixmanager.core.port.out.dict.DictGatewayPortOut;
import br.com.pixmanager.core.port.out.directory.AccountDirectoryPortOut;
import br.com.pixmanager.core.port.out.directory.KeyDirectoryPortOut;
import br.com.pixmanager.core.port.out.event.DomainEventPublisherPortOut;
import br.com.pixmanager.core.port.out.generator.IdGeneratorPortOut;
import br.com.pixmanager.core.port.out.ledger.LedgerGatewayPortOut;
import br.com.pixmanager.core.port.out.persistence.AccountQueryPortOut;
import br.com.pixmanager.core.port.out.persistence.ChargePersistencePortOut;
import br.com.pixmanager.core.port.out.persistence.ChargeQueryPortOut;
import br.com.pixmanager.core.port.out.persistence.LedgerReservationPersistencePortOut;
import br.com.pixmanager.core.port.out.persistence.LocationPortOut;
import br.com.pixmanager.core.port.out.persistence.PixKeyPersistencePortOut;
import br.com.pixmanager.core.port.out.persistence.TransactionRunnerPortOut;
import br.com.pixmanager.shared.properties.PixPolicyProperties;
import java.time.Clock;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Wiring único dos casos de uso: os services são classes puras (sem @Service, sem Spring no core) e viram bean aqui —
 * mesmo padrão do mesa-credito-full. Cada caso de uso que escreve é envolvido por {@link TransactionalUseCases}.
 */
@Configuration
public class UseCaseConfig {

    // ---- Ledger ----
    @Bean
    RequestReservationUseCase requestReservationUseCase(LedgerReservationPersistencePortOut persistence, IdGeneratorPortOut ids,
                                                        Clock clock, PlatformTransactionManager transactionManager) {
        return TransactionalUseCases.wrap(RequestReservationUseCase.class,
                new RequestReservationService(persistence, ids, clock), transactionManager);
    }

    /** Sem TransactionalUseCases: chama o core (HTTP); cada persistência abre a sua própria transação. */
    @Bean
    ExecuteReservationUseCase executeReservationUseCase(LedgerReservationPersistencePortOut persistence,
                                                        LedgerGatewayPortOut gateway, Clock clock) {
        return new ExecuteReservationService(persistence, gateway, clock);
    }

    // ---- Account ----

    @Bean
    FindAccountUseCase findAccountUseCase(AccountQueryPortOut query) {
        return new FindAccountService(query);
    }

    // ---- PixKey ----

    @Bean
    RegisterPixKeyUseCase registerPixKeyUseCase(PixKeyPersistencePortOut persistence, FindAccountUseCase accounts,
                                                DomainEventPublisherPortOut events, IdGeneratorPortOut ids, Clock clock,
                                                PixPolicyProperties policy, PlatformTransactionManager transactionManager) {
        return TransactionalUseCases.wrap(RegisterPixKeyUseCase.class,
                new RegisterPixKeyService(persistence, accounts, events, ids, clock,
                        policy.keys().maxPerAccountIndividual(), policy.keys().maxPerAccountBusiness()), transactionManager);
    }

    /** Orquestra HTTP (DICT) fora de transação: a janela transacional é aberta só no passo final, via TransactionRunnerPortOut. */
    @Bean
    ManagePixKeyUseCase managePixKeyUseCase(PixKeyPersistencePortOut persistence, DictGatewayPortOut dict,
                                            DomainEventPublisherPortOut events, TransactionRunnerPortOut transactionRunner, Clock clock) {
        return new ManagePixKeyService(persistence, dict, events, transactionRunner, clock);
    }

    /** Prova de posse por OTP. O envio (HTTP) fica FORA de transação; a gravação usa o TransactionRunnerPortOut. */
    @Bean
    OwnershipProofUseCase ownershipProofUseCase(PixKeyPersistencePortOut keys, OtpChallengePortOut challenges, OtpCodePortOut codes,
                                                OtpSenderPortOut sender, IdGeneratorPortOut ids, TransactionRunnerPortOut transactionRunner,
                                                Clock clock, PixPolicyProperties policy) {
        return new OwnershipProofService(keys, challenges, codes, sender, ids, transactionRunner, clock, policy.otp());
    }

    @Bean
    FindActiveKeyUseCase findActiveKeyUseCase(PixKeyPersistencePortOut persistence, FindAccountUseCase accounts,
                                              PlatformTransactionManager transactionManager) {
        return TransactionalUseCases.wrap(FindActiveKeyUseCase.class, new FindActiveKeyService(persistence, accounts), transactionManager);
    }

    @Bean
    LookupExternalKeyUseCase lookupExternalKeyUseCase(DictGatewayPortOut dict, FindAccountUseCase accounts) {
        return new LookupExternalKeyService(dict, accounts);
    }

    @Bean
    ActivatePendingKeysUseCase activatePendingKeysUseCase(PixKeyPersistencePortOut persistence, DictGatewayPortOut dict,
                                                          DomainEventPublisherPortOut events, TransactionRunnerPortOut transactionRunner, Clock clock,
                                                          PixPolicyProperties policy) {
        return new ActivatePendingKeysService(persistence, dict, events, transactionRunner, clock, policy.keys().activationMinAge());
    }

    // ---- Charge ----

    @Bean
    CreateChargeUseCase createChargeUseCase(ChargePersistencePortOut persistence, KeyDirectoryPortOut keys,
                                            LocationPortOut locations, DomainEventPublisherPortOut events, IdGeneratorPortOut ids,
                                            Clock clock, PixPolicyProperties policy, PlatformTransactionManager transactionManager) {
        return TransactionalUseCases.wrap(CreateChargeUseCase.class,
                new CreateChargeService(persistence, keys, locations, events, ids, clock,
                        policy.charge().defaultExpirationSeconds()), transactionManager);
    }

    @Bean
    FindChargeUseCase findChargeUseCase(ChargePersistencePortOut persistence, ChargeQueryPortOut query, KeyDirectoryPortOut keys,
                                        PlatformTransactionManager transactionManager) {
        return TransactionalUseCases.wrap(FindChargeUseCase.class, new FindChargeService(persistence, query, keys), transactionManager);
    }

    @Bean
    ReviseChargeUseCase reviseChargeUseCase(ChargePersistencePortOut persistence, ChargeQueryPortOut query, KeyDirectoryPortOut keys,
                                            DomainEventPublisherPortOut events, Clock clock, PlatformTransactionManager transactionManager) {
        return TransactionalUseCases.wrap(ReviseChargeUseCase.class,
                new ReviseChargeService(new FindChargeService(persistence, query, keys), persistence, events, clock), transactionManager);
    }

    @Bean
    SettleChargeUseCase settleChargeUseCase(ChargePersistencePortOut persistence, AccountDirectoryPortOut accounts,
                                            DomainEventPublisherPortOut events, PlatformTransactionManager transactionManager) {
        return TransactionalUseCases.wrap(SettleChargeUseCase.class, new SettleChargeService(persistence, accounts, events), transactionManager);
    }

    // ---- Pix recebido ----

    @Bean
    ReceivePixUseCase receivePixUseCase(InboxPortOut inbox, ReceivedPixPersistencePortOut persistence, FindAccountUseCase accounts,
                                        SettleChargeUseCase charges, DomainEventPublisherPortOut events, IdGeneratorPortOut ids,
                                        Clock clock, NotifyPixReceivedUseCase webhooks, PlatformTransactionManager transactionManager) {
        return TransactionalUseCases.wrap(ReceivePixUseCase.class,
                new ReceivePixService(inbox, persistence, accounts, charges, events, ids, clock, webhooks), transactionManager);
    }

    // ---- Webhook (notificação de Pix recebido) ----

    /** Enfileira a entrega NA TRANSAÇÃO do Pix recebido (por isso é chamado de dentro do ReceivePixUseCase embrulhado). */
    @Bean
    NotifyPixReceivedUseCase notifyPixReceivedUseCase(WebhookTargetPortOut targets, PixKeyPersistencePortOut keys,
                                                      WebhookSubscriptionPortOut subscriptions, WebhookDeliveryPortOut deliveries,
                                                      IdGeneratorPortOut ids, Clock clock) {
        return new WebhookNotificationService(targets, keys, subscriptions, deliveries, ids, clock);
    }

    /** Sem TransactionalUseCases: valida a URL (DNS) e cada operação de banco já é atômica; I/O de rede nunca dentro de transação. */
    @Bean
    ManageWebhookUseCase manageWebhookUseCase(PixKeyPersistencePortOut keys, WebhookSubscriptionPortOut subscriptions,
                                              WebhookUrlPolicyPortOut urlPolicy, IdGeneratorPortOut ids, Clock clock) {
        return new ManageWebhookService(keys, subscriptions, urlPolicy, ids, clock);
    }

    @Bean
    DispatchWebhooksUseCase dispatchWebhooksUseCase(WebhookDeliveryPortOut deliveries, WebhookSenderPortOut sender,
                                                    WebhookUrlPolicyPortOut urlPolicy, PixPolicyProperties policy, Clock clock) {
        PixPolicyProperties.Webhook webhook = policy.webhook();
        return new WebhookDispatchService(deliveries, sender, urlPolicy, new WebhookRetryPolicy(webhook.maxAttempts(), webhook.minDelay(), webhook.maxDelay(), 0.2),
                clock, () -> java.util.concurrent.ThreadLocalRandom.current().nextDouble());
    }

    @Bean
    FindReceivedPixUseCase findReceivedPixUseCase(ReceivedPixPersistencePortOut persistence, PlatformTransactionManager transactionManager) {
        return TransactionalUseCases.wrap(FindReceivedPixUseCase.class, new FindReceivedPixService(persistence), transactionManager);
    }

    // ---- Devolução ----

    @Bean
    RequestRefundUseCase requestRefundUseCase(ReceivedPixPersistencePortOut receivedPix, RefundPersistencePortOut refunds,
                                              RequestReservationUseCase reservations, DomainEventPublisherPortOut events,
                                              EndToEndIdGeneratorPortOut endToEndIdGenerator, IdGeneratorPortOut ids, Clock clock,
                                              PixPolicyProperties policy, PlatformTransactionManager transactionManager) {
        return TransactionalUseCases.wrap(RequestRefundUseCase.class,
                new RequestRefundService(receivedPix, refunds, reservations, events, endToEndIdGenerator, ids, clock,
                        policy.refund().window()), transactionManager);
    }

    @Bean
    FindRefundUseCase findRefundUseCase(RefundPersistencePortOut refunds, ReceivedPixPersistencePortOut receivedPix,
                                        PlatformTransactionManager transactionManager) {
        return TransactionalUseCases.wrap(FindRefundUseCase.class, new FindRefundService(refunds, receivedPix), transactionManager);
    }

    /** Orquestra HTTP (core) fora de transação; a janela transacional abre só no passo final, via TransactionRunnerPortOut. */
    @Bean
    SettleRefundUseCase settleRefundUseCase(RefundPersistencePortOut refunds, ReceivedPixPersistencePortOut receivedPix,
                                            ExecuteReservationUseCase ledger, DomainEventPublisherPortOut events, InboxPortOut inbox,
                                            TransactionRunnerPortOut transactionRunner) {
        return new SettleRefundService(refunds, receivedPix, ledger, events, inbox, transactionRunner);
    }

    @Bean
    ReservationOutcomeHandler refundReservationHandler(RefundPersistencePortOut refunds, ReceivedPixPersistencePortOut receivedPix,
                                                       DomainEventPublisherPortOut events, PlatformTransactionManager transactionManager) {
        return TransactionalUseCases.wrap(ReservationOutcomeHandler.class,
                new RefundReservationHandler(refunds, receivedPix, events), transactionManager);
    }

    // ---- Pix Automático ----

    /** Sem TransactionalUseCases: o gateway (HTTP) roda fora de transação; o service abre as próprias janelas. */
    @Bean
    ManageAutomaticUseCase manageAutomaticUseCase(AutomaticPersistencePortOut persistence, FindAccountUseCase accounts, AutomaticGatewayPortOut gateway,
                                                  DomainEventPublisherPortOut events, IdGeneratorPortOut ids, EndToEndIdGeneratorPortOut endToEndIdGenerator,
                                                  TransactionRunnerPortOut transactionRunner, Clock clock, AutomaticProperties properties) {
        return new ManageAutomaticService(persistence, accounts, gateway, events, ids, endToEndIdGenerator, transactionRunner, clock, properties);
    }

    @Bean
    ExecuteAutomaticUseCase executeAutomaticUseCase(AutomaticPersistencePortOut persistence, PaymentOrderPersistencePortOut orders,
                                                    CreatePaymentOrderUseCase createOrder, DomainEventPublisherPortOut events,
                                                    EndToEndIdGeneratorPortOut endToEndIdGenerator, IdGeneratorPortOut ids, TransactionRunnerPortOut transactionRunner, Clock clock,
                                                    AutomaticProperties properties) {
        return new ExecuteAutomaticService(persistence, orders, createOrder, events, endToEndIdGenerator, ids, transactionRunner, clock, properties);
    }

    // ---- MED ----

    /** Sem TransactionalUseCases: o gateway (DICT) e o core são HTTP e rodam FORA de transação; o service abre as próprias janelas. */
    @Bean
    MedCaseService medCaseService(MedCasePersistencePortOut cases, ReceivedPixPersistencePortOut receivedPix,
                                  PaymentOrderPersistencePortOut orders, RefundPersistencePortOut refunds, MedGatewayPortOut gateway,
                                  RequestReservationUseCase reservations, ExecuteReservationUseCase ledger, RequestRefundUseCase refundUseCase,
                                  DomainEventPublisherPortOut events, IdGeneratorPortOut ids, TransactionRunnerPortOut transactionRunner, Clock clock,
                                  PixPolicyProperties policy) {
        return new MedCaseService(cases, receivedPix, orders, refunds, gateway, reservations, ledger, refundUseCase, events, ids, transactionRunner, clock,
                policy.med());
    }

    @Bean
    ReservationOutcomeHandler medBlockReservationHandler(MedCasePersistencePortOut cases, DomainEventPublisherPortOut events, Clock clock,
                                                         PlatformTransactionManager transactionManager) {
        return TransactionalUseCases.wrap(ReservationOutcomeHandler.class, new MedBlockReservationHandler(cases, events, clock), transactionManager);
    }

    // ---- Reserva no core: reserva (HTTP, sem TX) e despacha ao fluxo dono ----

    @Bean
    ProcessReservationUseCase processReservationUseCase(ExecuteReservationUseCase execute, TransactionRunnerPortOut transactionRunner,
                                                        List<ReservationOutcomeHandler> handlers) {
        return new ProcessReservationService(execute, transactionRunner, handlers);
    }

    // ---- Limites ----

    /** Parâmetros regulatórios/de política dos limites vêm de {@code pix.policy.limits.*} (nunca constantes no core). */
    @Bean
    LimitPolicy.Config limitPolicyConfig(PixPolicyProperties policy) {
        var limitProperties = policy.limits();
        return new LimitPolicy.Config(limitProperties.zone(), limitProperties.nightStart(), limitProperties.nightEnd(), Money.of(limitProperties.nightIndividual()),
                Money.of(limitProperties.unregisteredDevicePerTx()), Money.of(limitProperties.unregisteredDeviceDaily()), Money.of(limitProperties.defaultDailyIndividual()),
                Money.of(limitProperties.defaultDailyBusiness()));
    }

    @Bean
    ConsumeLimitUseCase consumeLimitUseCase(LimitPersistencePortOut limits, LimitUsagePortOut usage, FindAccountUseCase accounts,
                                            LimitPolicy.Config config, PlatformTransactionManager transactionManager) {
        return TransactionalUseCases.wrap(ConsumeLimitUseCase.class, new ConsumeLimitService(limits, usage, accounts, config), transactionManager);
    }

    @Bean
    ManageLimitUseCase manageLimitUseCase(LimitPersistencePortOut limits, FindAccountUseCase accounts, IdGeneratorPortOut ids, Clock clock,
                                          LimitPolicy.Config defaults, PixPolicyProperties policy, PlatformTransactionManager transactionManager) {
        var limitProperties = policy.limits();
        var ceilings = new ManageLimitService.Ceilings(Money.of(limitProperties.ceilingIndividual()), Money.of(limitProperties.ceilingBusiness()), limitProperties.increaseDelayMin());
        return TransactionalUseCases.wrap(ManageLimitUseCase.class, new ManageLimitService(limits, accounts, ids, clock, defaults, ceilings), transactionManager);
    }

    // ---- Pix Agendado ----

    @Bean
    ManageScheduledPaymentUseCase manageScheduledPaymentUseCase(ScheduledPaymentPersistencePortOut persistence, FindAccountUseCase accounts,
                                                                DomainEventPublisherPortOut events, IdGeneratorPortOut ids, Clock clock,
                                                                ScheduleProperties properties, PlatformTransactionManager transactionManager) {
        return TransactionalUseCases.wrap(ManageScheduledPaymentUseCase.class,
                new ManageScheduledPaymentService(persistence, accounts, events, ids, clock, properties), transactionManager);
    }

    /** Sem TransactionalUseCases: o DICT (HTTP) roda fora de transação; o service abre as suas janelas transacionais. */
    @Bean
    ExecuteScheduledPaymentsUseCase executeScheduledPaymentsUseCase(ScheduledPaymentPersistencePortOut schedules,
                                                                    PaymentOrderPersistencePortOut orders, CreatePaymentOrderUseCase createOrder,
                                                                    DomainEventPublisherPortOut events, TransactionRunnerPortOut transactionRunner, Clock clock,
                                                                    ScheduleProperties properties, PixPolicyProperties policy) {
        var window = policy.scheduling();
        ScheduleRetryPolicy retry = new ScheduleRetryPolicy(properties.zone(), window.retryWindowStart(), window.retryWindowEnd(), 2);
        return new ExecuteScheduledPaymentsService(schedules, orders, createOrder, events, transactionRunner, clock, properties, retry,
                () -> java.util.concurrent.ThreadLocalRandom.current().nextDouble());
    }

    // ---- Pagamento (ordens Pix) ----

    /** Sem TransactionalUseCases: resolve o favorecido no DICT (HTTP) antes e abre a transação só para limite + reserva + ordem + evento. */
    @Bean
    CreatePaymentOrderUseCase createPaymentOrderUseCase(FindAccountUseCase accounts, LookupExternalKeyUseCase lookup,
                                                        ConsumeLimitUseCase limits, RequestReservationUseCase reservations,
                                                        PaymentOrderPersistencePortOut persistence, TrustedDevicePortOut devices,
                                                        DomainEventPublisherPortOut events, EndToEndIdGeneratorPortOut endToEndIdGenerator,
                                                        IdGeneratorPortOut ids, TransactionRunnerPortOut transactionRunner, Clock clock,
                                                        org.springframework.beans.factory.ObjectProvider<br.com.pixmanager.core.port.out.DynamicQrPayloadPortOut> dynamicQr) {
        return new CreatePaymentOrderService(accounts, lookup, limits, reservations, persistence, devices, events, endToEndIdGenerator, ids, transactionRunner, clock,
                dynamicQr.getIfAvailable());
    }

    @Bean
    FindPaymentOrderUseCase findPaymentOrderUseCase(PaymentOrderPersistencePortOut persistence, PlatformTransactionManager transactionManager) {
        return TransactionalUseCases.wrap(FindPaymentOrderUseCase.class, new FindPaymentOrderService(persistence), transactionManager);
    }

    @Bean
    CancelPaymentOrderUseCase cancelPaymentOrderUseCase(PaymentOrderPersistencePortOut persistence, ExecuteReservationUseCase ledger,
                                                        ConsumeLimitUseCase limits, DomainEventPublisherPortOut events,
                                                        TransactionRunnerPortOut transactionRunner, PixPolicyProperties policy) {
        return new CancelPaymentOrderService(persistence, ledger, limits, events, transactionRunner, policy.limits().zone());
    }

    @Bean
    ApplyPaymentStatusUseCase applyPaymentStatusUseCase(PaymentOrderPersistencePortOut persistence, ExecuteReservationUseCase ledger,
                                                        ConsumeLimitUseCase limits, DomainEventPublisherPortOut events, InboxPortOut inbox,
                                                        TransactionRunnerPortOut transactionRunner, PixPolicyProperties policy) {
        return new ApplyPaymentStatusService(persistence, ledger, limits, events, inbox, transactionRunner, policy.limits().zone());
    }

    @Bean
    DecodeBrCodeUseCase decodeBrCodeUseCase() {
        return new DecodeBrCodeService();
    }

    @Bean
    ReservationOutcomeHandler paymentReservationHandler(PaymentOrderPersistencePortOut persistence, ConsumeLimitUseCase limits,
                                                        DomainEventPublisherPortOut events, PixPolicyProperties policy,
                                                        PlatformTransactionManager transactionManager) {
        return TransactionalUseCases.wrap(ReservationOutcomeHandler.class,
                new PaymentReservationHandler(persistence, limits, events, policy.limits().zone()), transactionManager);
    }
}
