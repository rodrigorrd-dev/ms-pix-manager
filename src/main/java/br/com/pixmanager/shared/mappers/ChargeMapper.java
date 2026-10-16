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
    public CreateChargeCommand toCommand(String txid, CobRequest r) {
        return new CreateChargeCommand(ChargeType.IMMEDIATE, txid, r.pixKey(), Money.parseBcb(r.value().original()),
                r.calendar() == null ? null : r.calendar().expiration(), null, null, toDebtor(r.debtor()), r.payerRequest(),
                toInfo(r.additionalInfo()));
    }

    public CreateChargeCommand toCommand(String txid, CobvRequest r) {
        return new CreateChargeCommand(ChargeType.DUE, txid, r.pixKey(), Money.parseBcb(r.value().original()), null,
                r.calendar().dueDate(), r.calendar().validityAfterDueDays(), toDebtor(r.debtor()), r.payerRequest(),
                toInfo(r.additionalInfo()));
    }

    public ReviseChargeCommand toCommand(CobRevisionRequest r) {
        boolean remove = r.status() != null;
        if (remove && r.status() != ChargeStatus.REMOVIDA_PELO_USUARIO_RECEBEDOR) {
            throw new BadRequestException(ErrorCodes.INVALID_PAYLOAD, "Somente REMOVIDA_PELO_USUARIO_RECEBEDOR é aceito em status.");
        }
        return new ReviseChargeCommand(remove, r.value() == null ? null : Money.parseBcb(r.value().original()),
                toDebtor(r.debtor()), r.payerRequest(), r.calendar() == null ? null : r.calendar().expiration());
    }

    /** {@code cpf} XOR {@code cnpj}; o VO valida o dígito verificador (422 sem eco do valor). */
    public Debtor toDebtor(DebtorRequest r) {
        if (r == null) {
            return null;
        }
        boolean hasCpf = r.cpf() != null && !r.cpf().isBlank();
        boolean hasCnpj = r.cnpj() != null && !r.cnpj().isBlank();
        if (hasCpf == hasCnpj) {
            throw new BadRequestException(ErrorCodes.INVALID_PAYLOAD, "O devedor exige exatamente um entre cpf e cnpj.");
        }
        Document document = hasCpf ? Cpf.of(r.cpf()) : Cnpj.of(r.cnpj());
        return new Debtor(document, r.name());
    }

    private List<AdditionalInfo> toInfo(List<AdditionalInfoRequest> items) {
        return items == null ? List.of() : items.stream().map(i -> new AdditionalInfo(i.name(), i.value())).toList();
    }

    // ---------------------------------------------------------------- domínio → response
    public ChargeResponse toResponse(ChargeDetails details, boolean fullDocument) {
        Charge c = details.charge();
        PixLocation loc = c.location();
        ChargeResponse.Calendar calendar = c.type() == ChargeType.IMMEDIATE
                ? new ChargeResponse.Calendar(c.createdAt(), c.expirationSeconds(), null, null)
                : new ChargeResponse.Calendar(c.createdAt(), null, c.dueDate(), c.validityAfterDueDays());
        String locationUrl = loc == null ? null : locationBase + loc.urlPath();
        return new ChargeResponse(calendar, c.txid().value(), c.revision(),
                loc == null ? null : new ChargeResponse.Location(loc.locationId(), locationUrl,
                        c.type() == ChargeType.IMMEDIATE ? "cob" : "cobv", c.createdAt()),
                locationUrl, c.status(), toDebtorResponse(c.debtor(), fullDocument),
                new ChargeResponse.Value(c.originalAmount().toBcb()), details.pixKey(), c.payerRequest(),
                c.additionalInfo().isEmpty() ? null
                        : c.additionalInfo().stream().map(i -> new ChargeResponse.Info(i.name(), i.value())).toList());
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
                page.content().stream().map(d -> toResponse(d, fullDocument)).toList());
    }
}
