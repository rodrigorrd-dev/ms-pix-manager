package br.com.pixmanager.core.port.in.automatic;

import br.com.pixmanager.core.domain.entity.AutomaticAttempt;
import br.com.pixmanager.core.domain.entity.AutomaticAuthorization;
import br.com.pixmanager.core.domain.entity.AutomaticCharge;
import br.com.pixmanager.core.domain.entity.Caller;
import br.com.pixmanager.core.domain.entity.Money;
import br.com.pixmanager.core.domain.enums.AutomaticPeriodicity;
import br.com.pixmanager.shared.model.dto.PageQuery;
import br.com.pixmanager.shared.model.dto.PageResult;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface ManageAutomaticUseCase {

    /** Entrada da solicitação do recebedor (no produto vem do spi-gateway; aqui, também por API de back-office). BR-AUT-01/03. */
    AutomaticAuthorization receiveAuthorization(Caller caller, ReceiveAuthorizationCommand command);

    AutomaticAuthorization getAuthorization(Caller caller, UUID id);

    PageResult<AutomaticAuthorization> listAuthorizations(Caller caller, String status, PageQuery page);

    /** O pagador aceita (opcionalmente limitando o valor) e o recebedor é avisado. BR-AUT-02. */
    AutomaticAuthorization approve(Caller caller, UUID id, Money payerMaxAmount);

    AutomaticAuthorization reject(Caller caller, UUID id, String rejectionCode);

    /** Cancela a autorização e as cobranças/tentativas em aberto; o recebedor é avisado. BR-AUT-04. */
    AutomaticAuthorization cancel(Caller caller, UUID id, String cancelledBy);

    AutomaticAuthorization adjustLimit(Caller caller, UUID id, Money payerMaxAmount);

    /** Cobrança recorrente do recebedor para uma autorização aprovada (BR-AUT-05); cria a tentativa AGND na data de vencimento. */
    AutomaticCharge registerCharge(Caller caller, UUID authorizationId, RegisterChargeCommand command);

    List<AutomaticCharge> charges(Caller caller, UUID authorizationId);

    List<AutomaticAttempt> attempts(Caller caller, UUID authorizationId, UUID chargeId);

    /** O pagador cancela a cobrança até 23h59 da véspera do vencimento. */
    AutomaticCharge cancelCharge(Caller caller, UUID authorizationId, UUID chargeId);

    record ReceiveAuthorizationCommand(String idRec, UUID payerAccountId, String payerDocument, String receiverDocument, String receiverName,
                                       String contract, String objectDescription, AutomaticPeriodicity periodicity, LocalDate startDate,
                                       LocalDate endDate, Money fixedAmount, Money receiverMinAmount) {}

    record RegisterChargeCommand(String txid, LocalDate dueDate, Money amount, String receiverIspb, String receiverBranch, String receiverAccount,
                                 String receiverAccountType) {}
}
