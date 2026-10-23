package br.com.pixmanager.api.rest.charge;

import br.com.pixmanager.api.rest.charge.dto.request.CobvRequest;
import br.com.pixmanager.api.rest.charge.dto.response.ChargeResponse;
import br.com.pixmanager.api.rest.security.CallerContext;
import br.com.pixmanager.core.domain.entity.Caller;
import br.com.pixmanager.core.domain.entity.TxId;
import br.com.pixmanager.core.domain.enums.ChargeType;
import br.com.pixmanager.core.port.in.charge.CreateChargeUseCase;
import br.com.pixmanager.core.port.in.charge.CreateChargeUseCase.CreateChargeResult;
import br.com.pixmanager.core.port.in.charge.FindChargeUseCase;
import br.com.pixmanager.shared.constant.ErrorCodes;
import br.com.pixmanager.shared.util.ETags;
import br.com.pixmanager.shared.exception.ConflictException;
import br.com.pixmanager.shared.exception.NotFoundException;
import br.com.pixmanager.shared.mappers.ChargeMapper;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;

@RestController
public class DueChargeControllerImpl implements DueChargeController {

    private final CreateChargeUseCase create;
    private final FindChargeUseCase find;
    private final ChargeMapper mapper;

    public DueChargeControllerImpl(CreateChargeUseCase create, FindChargeUseCase find, ChargeMapper mapper) {
        this.create = create;
        this.find = find;
        this.mapper = mapper;
    }

    @Override
    public ResponseEntity<ChargeResponse> create(String txid, CobvRequest request) {
        Caller caller = CallerContext.current();
        CreateChargeResult result = create.create(caller, mapper.toCommand(new TxId(txid).value(), request));
        return switch (result) {
            case CreateChargeResult.Created created -> {
                URI location = ServletUriComponentsBuilder.fromCurrentRequestUri().build().toUri();
                yield ResponseEntity.created(location).eTag(String.valueOf(created.details().charge().version()))
                        .cacheControl(CacheControl.noStore()).body(mapper.toResponse(created.details(), caller.fullDocument()));
            }
            case CreateChargeResult.AlreadyExists existing -> ResponseEntity.status(HttpStatus.OK)
                    .eTag(String.valueOf(existing.details().charge().version())).cacheControl(CacheControl.noStore())
                    .body(mapper.toResponse(existing.details(), caller.fullDocument()));
            case CreateChargeResult.TxIdConflict conflict -> throw new ConflictException(ErrorCodes.TXID_CONFLICT,
                    "Já existe cobrança com este txid para o recebedor com conteúdo diferente.");
        };
    }

    @Override
    public ResponseEntity<ChargeResponse> find(String txid, String ifNoneMatch) {
        Caller caller = CallerContext.current();
        var details = find.findByTxid(caller, new TxId(txid))
                .filter(dueCharge -> dueCharge.charge().type() == ChargeType.DUE)
                .orElseThrow(() -> new NotFoundException(ErrorCodes.NOT_FOUND, "Cobrança não encontrada."));
        if (ETags.noneMatch(ifNoneMatch, details.charge().version())) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.NOT_MODIFIED).eTag(String.valueOf(details.charge().version())).build();
        }
        return ResponseEntity.ok().eTag(String.valueOf(details.charge().version())).cacheControl(CacheControl.noStore())
                .body(mapper.toResponse(details, caller.fullDocument()));
    }
}
