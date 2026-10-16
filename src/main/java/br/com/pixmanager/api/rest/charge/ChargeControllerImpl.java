package br.com.pixmanager.api.rest.charge;

import br.com.pixmanager.api.rest.charge.dto.request.CobRequest;
import br.com.pixmanager.api.rest.charge.dto.request.CobRevisionRequest;
import br.com.pixmanager.api.rest.charge.dto.response.ChargePageResponse;
import br.com.pixmanager.api.rest.charge.dto.response.ChargeResponse;
import br.com.pixmanager.api.rest.security.CallerContext;
import br.com.pixmanager.core.domain.entity.Caller;
import br.com.pixmanager.core.domain.entity.TxId;
import br.com.pixmanager.core.domain.enums.ChargeStatus;
import br.com.pixmanager.core.port.in.charge.ChargeDetails;
import br.com.pixmanager.core.port.in.charge.CreateChargeUseCase;
import br.com.pixmanager.core.port.in.charge.CreateChargeUseCase.CreateChargeResult;
import br.com.pixmanager.core.port.in.charge.FindChargeUseCase;
import br.com.pixmanager.core.port.in.charge.FindChargeUseCase.ChargeSearch;
import br.com.pixmanager.core.port.in.charge.ReviseChargeUseCase;
import br.com.pixmanager.shared.constant.ErrorCodes;
import br.com.pixmanager.shared.util.ETags;
import br.com.pixmanager.shared.exception.BadRequestException;
import br.com.pixmanager.shared.exception.ConflictException;
import br.com.pixmanager.shared.exception.NotFoundException;
import br.com.pixmanager.shared.mappers.ChargeMapper;
import br.com.pixmanager.shared.model.ExpectedVersion;
import br.com.pixmanager.shared.model.document.Cnpj;
import br.com.pixmanager.shared.model.document.Cpf;
import br.com.pixmanager.shared.model.document.Document;
import br.com.pixmanager.shared.model.dto.PageQuery;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.time.Instant;
import java.util.Optional;

/** Só traduz HTTP ⇄ caso de uso (nenhuma regra de negócio aqui). Resultados selados viram status HTTP. */
@RestController
public class ChargeControllerImpl implements ChargeController {

    private final CreateChargeUseCase create;
    private final FindChargeUseCase find;
    private final ReviseChargeUseCase revise;
    private final ChargeMapper mapper;

    public ChargeControllerImpl(CreateChargeUseCase create, FindChargeUseCase find, ReviseChargeUseCase revise, ChargeMapper mapper) {
        this.create = create;
        this.find = find;
        this.revise = revise;
        this.mapper = mapper;
    }

    @Override
    public ResponseEntity<ChargeResponse> create(String txid, CobRequest request) {
        return respond(create.create(CallerContext.current(), mapper.toCommand(new TxId(txid).value(), request)), CallerContext.current());
    }

    @Override
    public ResponseEntity<ChargeResponse> createWithGeneratedTxid(CobRequest request) {
        return respond(create.create(CallerContext.current(), mapper.toCommand(null, request)), CallerContext.current());
    }

    private ResponseEntity<ChargeResponse> respond(CreateChargeResult result, Caller caller) {
        return switch (result) {
            case CreateChargeResult.Created created -> {
                ChargeResponse body = mapper.toResponse(created.details(), caller.fullDocument());
                URI location = ServletUriComponentsBuilder.fromCurrentRequestUri().replacePath("/api/pix/v2/cob/{txid}")
                        .buildAndExpand(body.txid()).toUri();
                yield ResponseEntity.created(location).eTag(etag(created.details())).cacheControl(CacheControl.noStore()).body(body);
            }
            case CreateChargeResult.AlreadyExists existing -> ResponseEntity.status(HttpStatus.OK)
                    .eTag(etag(existing.details())).cacheControl(CacheControl.noStore())
                    .body(mapper.toResponse(existing.details(), caller.fullDocument()));
            case CreateChargeResult.TxIdConflict conflict -> throw new ConflictException(ErrorCodes.TXID_CONFLICT,
                    "Já existe cobrança com este txid para o recebedor com conteúdo diferente.");
        };
    }

    @Override
    public ResponseEntity<ChargeResponse> revise(String txid, String ifMatch, CobRevisionRequest request) {
        ExpectedVersion expected = ExpectedVersion.parse(ifMatch);
        Caller caller = CallerContext.current();
        ChargeDetails revised = revise.revise(caller, new TxId(txid), expected, mapper.toCommand(request));
        return ResponseEntity.ok().eTag(etag(revised)).cacheControl(CacheControl.noStore())
                .body(mapper.toResponse(revised, caller.fullDocument()));
    }

    @Override
    public ResponseEntity<ChargeResponse> find(String txid, String ifNoneMatch) {
        Caller caller = CallerContext.current();
        ChargeDetails details = find.findByTxid(caller, new TxId(txid))
                .orElseThrow(() -> new NotFoundException(ErrorCodes.NOT_FOUND, "Cobrança não encontrada."));
        String etag = etag(details);
        if (ETags.noneMatch(ifNoneMatch, details.charge().version())) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
        }
        return ResponseEntity.ok().eTag(etag).cacheControl(CacheControl.noStore())
                .body(mapper.toResponse(details, caller.fullDocument()));
    }

    @Override
    public ResponseEntity<ChargePageResponse> search(Instant from, Instant to, String cpf, String cnpj, ChargeStatus status,
                                                     Boolean locationPresent, int page, int pageSize) {
        if (cpf != null && cnpj != null) {
            throw new BadRequestException(ErrorCodes.INVALID_PAYLOAD, "Informe cpf ou cnpj, não ambos.");
        }
        if (from.isAfter(to)) {
            throw new BadRequestException(ErrorCodes.INVALID_PAYLOAD, "inicio deve ser anterior ou igual a fim.");
        }
        Optional<Document> debtor = cpf != null ? Optional.of(Cpf.of(cpf)) : cnpj != null ? Optional.of(Cnpj.of(cnpj)) : Optional.empty();
        Caller caller = CallerContext.current();
        var result = find.search(caller, new ChargeSearch(from, to, Optional.ofNullable(status), debtor,
                Optional.ofNullable(locationPresent)), PageQuery.of(page, pageSize));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(mapper.toPage(result, from, to, caller.fullDocument()));
    }

    private static String etag(ChargeDetails details) {
        return String.valueOf(details.charge().version());
    }
}
