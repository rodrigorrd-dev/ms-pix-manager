package br.com.pixmanager.core.port.in.med;

import br.com.pixmanager.core.domain.entity.Caller;
import br.com.pixmanager.core.domain.entity.MedCase;
import br.com.pixmanager.core.domain.entity.MedContestation;
import br.com.pixmanager.core.domain.enums.MedCaseType;
import br.com.pixmanager.core.domain.enums.MedRole;
import br.com.pixmanager.core.domain.enums.MedStatus;
import br.com.pixmanager.core.port.out.persistence.MedCasePersistencePortOut.HistoryEntry;
import br.com.pixmanager.shared.model.dto.PageQuery;
import br.com.pixmanager.shared.model.dto.PageResult;

import java.util.List;
import java.util.UUID;

public interface ManageMedCaseUseCase {

    /** REPORTED: o nosso cliente RECEBEU o Pix. REPORTER: o nosso cliente PAGOU (e o caso é enviado ao DICT). */
    MedCase open(Caller caller, OpenMedCaseCommand command);

    MedCase get(Caller caller, UUID id);

    PageResult<MedCase> list(Caller caller, MedStatus status, PageQuery page);

    List<HistoryEntry> history(Caller caller, UUID id);

    /** REPORTED, OPEN → ACCEPTED e pede o bloqueio cautelar do valor no core (BR-MED-03). */
    MedCase accept(Caller caller, UUID id, String note);

    /** REPORTED, OPEN → REFUSED. */
    MedCase refuse(Caller caller, UUID id, String note);

    /** OPEN|ACCEPTED → CANCELLED; libera o bloqueio; em REPORTER avisa o DICT. */
    MedCase cancel(Caller caller, UUID id, String note);

    /** REPORTED, ACCEPTED com valor bloqueado: libera o bloqueio e devolve o valor com natureza MED (BR-MED-06). */
    MedCase startRefund(Caller caller, UUID id);

    /** REPORTER, OPEN → CLOSED: o resultado do caso foi registrado pela operação. */
    MedCase close(Caller caller, UUID id, String note);

    /** Contestação da devolução pelo recebedor, em até 80 dias da devolução (BR-MED-07). */
    MedContestation contest(Caller caller, UUID caseId, String reason);

    MedContestation decideContestation(Caller caller, UUID contestationId, boolean accepted, String note);

    record OpenMedCaseCommand(MedRole role, String endToEndId, MedCaseType caseType, String description, Integer layer) {}
}
