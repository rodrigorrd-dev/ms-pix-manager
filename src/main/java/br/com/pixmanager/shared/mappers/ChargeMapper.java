package br.com.pixmanager.shared.mappers;

import br.com.pixmanager.api.rest.charge.dto.request.AdditionalInfoRequest;
import br.com.pixmanager.api.rest.charge.dto.request.CobRequest;
import br.com.pixmanager.api.rest.charge.dto.request.CobRevisionRequest;
import br.com.pixmanager.api.rest.charge.dto.request.CobvRequest;
import br.com.pixmanager.api.rest.charge.dto.request.DebtorRequest;
import br.com.pixmanager.api.rest.charge.dto.response.ChargePageResponse;
import br.com.pixmanager.api.rest.charge.dto.response.ChargeResponse;
import br.com.pixmanager.core.domain.entity.AdditionalInfo;
import br.com.pixmanager.core.domain.entity.Charge;
import br.com.pixmanager.core.domain.entity.Debtor;
import br.com.pixmanager.core.domain.entity.Money;
import br.com.pixmanager.core.domain.entity.PixLocation;
import br.com.pixmanager.core.domain.enums.ChargeStatus;
import br.com.pixmanager.core.domain.enums.ChargeType;
import br.com.pixmanager.core.port.in.charge.ChargeDetails;
import br.com.pixmanager.core.port.in.charge.CreateChargeUseCase.CreateChargeCommand;
import br.com.pixmanager.core.port.in.charge.ReviseChargeUseCase.ReviseChargeCommand;
import br.com.pixmanager.shared.constant.ErrorCodes;
import br.com.pixmanager.shared.exception.BadRequestException;
import br.com.pixmanager.shared.model.document.Cnpj;
import br.com.pixmanager.shared.model.document.Cpf;
import br.com.pixmanager.shared.model.document.Document;
import br.com.pixmanager.shared.model.dto.PageResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Borda REST ⇄ domínio da cobrança. Aqui (e só aqui) se traduz o idioma do BCB (campos em português) para o domínio em
 * inglês e se decide mascarar o documento do devedor (decisão B8): completo apenas com {@code pii.document.read}.
 */
@Component
public class ChargeMapper {

    private final String locationBase;

    public ChargeMapper(@Value("${pix.qr.base-url:pix.example.com/qr/v2/}") String locationBase) {
        this.locationBase = locationBase.endsWith("/") ? locationBase : locationBase + "/";
    }

    // ---------------------------------------------------------------- request → comando
    public CreateChargeCommand toCommand(String txid, CobRequest request) {
        return new CreateChargeCommand(ChargeType.IMMEDIATE, txid, request.pixKey(), Money.parseBcb(request.value().original()),
                request.calendar() == null ? null : request.calendar().expiration(), null, null, toDebtor(request.debtor()), request.payerRequest(),
                toInfo(request.additionalInfo()));
    }

    public CreateChargeCommand toCommand(String txid, CobvRequest request) {
        return new CreateChargeCommand(ChargeType.DUE, txid, request.pixKey(), Money.parseBcb(request.value().original()), null,
                request.calendar().dueDate(), request.calendar().validityAfterDueDays(), toDebtor(request.debtor()), request.payerRequest(),
                toInfo(request.additionalInfo()));
    }

    public ReviseChargeCommand toCommand(CobRevisionRequest request) {
        boolean remove = request.status() != null;
        if (remove && request.status() != ChargeStatus.REMOVIDA_PELO_USUARIO_RECEBEDOR) {
            throw new BadRequestException(ErrorCodes.INVALID_PAYLOAD, "Somente REMOVIDA_PELO_USUARIO_RECEBEDOR é aceito em status.");
        }
        return new ReviseChargeCommand(remove, request.value() == null ? null : Money.parseBcb(request.value().original()),
                toDebtor(request.debtor()), request.payerRequest(), request.calendar() == null ? null : request.calendar().expiration());
    }

    /** {@code cpf} XOR {@code cnpj}; o VO valida o dígito verificador (422 sem eco do valor). */
    public Debtor toDebtor(DebtorRequest request) {
        if (request == null) {
            return null;
        }
        boolean hasCpf = request.cpf() != null && !request.cpf().isBlank();
        boolean hasCnpj = request.cnpj() != null && !request.cnpj().isBlank();
        if (hasCpf == hasCnpj) {
            throw new BadRequestException(ErrorCodes.INVALID_PAYLOAD, "O devedor exige exatamente um entre cpf e cnpj.");
        }
        Document document = hasCpf ? Cpf.of(request.cpf()) : Cnpj.of(request.cnpj());
        return new Debtor(document, request.name());
    }

    private List<AdditionalInfo> toInfo(List<AdditionalInfoRequest> items) {
        return items == null ? List.of() : items.stream().map(info -> new AdditionalInfo(info.name(), info.value())).toList();
    }

    // ---------------------------------------------------------------- domínio → response
    public ChargeResponse toResponse(ChargeDetails details, boolean fullDocument) {
        Charge charge = details.charge();
        PixLocation location = charge.location();
        ChargeResponse.Calendar calendar = charge.type() == ChargeType.IMMEDIATE
                ? new ChargeResponse.Calendar(charge.createdAt(), charge.expirationSeconds(), null, null)
                : new ChargeResponse.Calendar(charge.createdAt(), null, charge.dueDate(), charge.validityAfterDueDays());
        String locationUrl = location == null ? null : locationBase + location.urlPath();
        return new ChargeResponse(calendar, charge.txid().value(), charge.revision(),
                location == null ? null : new ChargeResponse.Location(location.locationId(), locationUrl,
                        charge.type() == ChargeType.IMMEDIATE ? "cob" : "cobv", charge.createdAt()),
                locationUrl, charge.status(), toDebtorResponse(charge.debtor(), fullDocument),
                new ChargeResponse.Value(charge.originalAmount().toBcb()), details.pixKey(), charge.payerRequest(),
                charge.additionalInfo().isEmpty() ? null
                        : charge.additionalInfo().stream().map(info -> new ChargeResponse.Info(info.name(), info.value())).toList());
    }

    private ChargeResponse.Debtor toDebtorResponse(Debtor debtor, boolean fullDocument) {
        if (debtor == null) {
            return null;
        }
        String shown = fullDocument ? debtor.document().value() : debtor.document().masked();
        return debtor.document() instanceof Cpf
                ? new ChargeResponse.Debtor(shown, null, debtor.name())
                : new ChargeResponse.Debtor(null, shown, debtor.name());
    }

    public ChargePageResponse toPage(PageResult<ChargeDetails> page, Instant from, Instant to, boolean fullDocument) {
        return new ChargePageResponse(
                new ChargePageResponse.Parameters(from, to, new ChargePageResponse.Pagination(page.page(), page.size(),
                        page.totalPages(), page.totalElements())),
                page.content().stream().map(charge -> toResponse(charge, fullDocument)).toList());
    }
}
