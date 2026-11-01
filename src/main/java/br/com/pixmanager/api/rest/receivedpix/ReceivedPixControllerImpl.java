package br.com.pixmanager.api.rest.receivedpix;

import br.com.pixmanager.api.rest.receivedpix.dto.response.ReceivedPixPageResponse;
import br.com.pixmanager.api.rest.receivedpix.dto.response.ReceivedPixResponse;
import br.com.pixmanager.shared.security.CallerContext;
import br.com.pixmanager.core.domain.entity.Caller;
import br.com.pixmanager.core.domain.entity.EndToEndId;
import br.com.pixmanager.core.domain.entity.ReceivedPix;
import br.com.pixmanager.core.domain.entity.Refund;
import br.com.pixmanager.core.port.in.receivedpix.FindReceivedPixUseCase;
import br.com.pixmanager.core.port.in.receivedpix.FindReceivedPixUseCase.ReceivedPixSearch;
import br.com.pixmanager.core.port.in.refund.FindRefundUseCase;
import br.com.pixmanager.shared.constant.ErrorCodes;
import br.com.pixmanager.shared.exception.BadRequestException;
import br.com.pixmanager.shared.exception.NotFoundException;
import br.com.pixmanager.shared.mappers.ReceivedPixMapper;
import br.com.pixmanager.shared.model.document.Cnpj;
import br.com.pixmanager.shared.model.document.Cpf;
import br.com.pixmanager.shared.model.document.Document;
import br.com.pixmanager.shared.model.dto.PageQuery;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@RestController
public class ReceivedPixControllerImpl implements ReceivedPixController {

    private final FindReceivedPixUseCase find;
    private final FindRefundUseCase refunds;
    private final ReceivedPixMapper mapper;

    public ReceivedPixControllerImpl(FindReceivedPixUseCase find, FindRefundUseCase refunds, ReceivedPixMapper mapper) {
        this.find = find;
        this.refunds = refunds;
        this.mapper = mapper;
    }

    @Override
    public ResponseEntity<ReceivedPixResponse> find(String endToEndId) {
        Caller caller = CallerContext.current();
        ReceivedPix pix = find.findByEndToEndId(caller, new EndToEndId(endToEndId).value())
                .orElseThrow(() -> new NotFoundException(ErrorCodes.NOT_FOUND, "Pix não encontrado."));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(mapper.toResponse(pix, refundsOf(caller, pix), caller.fullDocument()));
    }

    @Override
    public ResponseEntity<ReceivedPixPageResponse> search(Instant from, Instant to, String txid, String cpf, String cnpj, int page,
                                                          int pageSize) {
        if (cpf != null && cnpj != null) {
            throw new BadRequestException(ErrorCodes.INVALID_PAYLOAD, "Informe cpf ou cnpj, não ambos.");
        }
        if (from.isAfter(to)) {
            throw new BadRequestException(ErrorCodes.INVALID_PAYLOAD, "inicio deve ser anterior ou igual a fim.");
        }
        Optional<Document> payer = cpf != null ? Optional.of(Cpf.of(cpf)) : cnpj != null ? Optional.of(Cnpj.of(cnpj)) : Optional.empty();
        Caller caller = CallerContext.current();
        var result = find.search(caller, new ReceivedPixSearch(from, to, Optional.ofNullable(txid), payer), PageQuery.of(page, pageSize));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(mapper.toPage(result, receivedPix -> refundsOf(caller, receivedPix), from, to, caller.fullDocument()));
    }

    private List<Refund> refundsOf(Caller caller, ReceivedPix pix) {
        return refunds.listByReceived(caller, pix.id(), pix.receiverAccountId());
    }
}
