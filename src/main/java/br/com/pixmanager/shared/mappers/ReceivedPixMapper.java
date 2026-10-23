package br.com.pixmanager.shared.mappers;

import br.com.pixmanager.api.rest.receivedpix.dto.response.ReceivedPixPageResponse;
import br.com.pixmanager.api.rest.receivedpix.dto.response.ReceivedPixResponse;
import br.com.pixmanager.core.domain.entity.PixPayer;
import br.com.pixmanager.core.domain.entity.ReceivedPix;
import br.com.pixmanager.core.domain.entity.Refund;
import br.com.pixmanager.shared.model.document.Cpf;
import br.com.pixmanager.shared.model.dto.PageResult;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.function.Function;

/** Documento do pagador MASCARADO por padrão; completo só com {@code pii.document.read} (decisão B8). */
@Component
public class ReceivedPixMapper {

    private final RefundMapper refundMapper;

    public ReceivedPixMapper(RefundMapper refundMapper) {
        this.refundMapper = refundMapper;
    }

    public ReceivedPixResponse toResponse(ReceivedPix receivedPix, List<Refund> refunds, boolean fullDocument) {
        return new ReceivedPixResponse(receivedPix.endToEndId().value(), receivedPix.txid(), receivedPix.amount().toBcb(), receivedPix.settledAt(),
                payer(receivedPix.payer(), fullDocument), receivedPix.payerInfo(),
                receivedPix.components().size() == 1 ? null : receivedPix.components().stream()
                        .map(component -> new ReceivedPixResponse.Component(component.type().name(), component.amount().toBcb())).toList(),
                refunds.isEmpty() ? null : refunds.stream().map(refundMapper::toResponse).toList());
    }

    private ReceivedPixResponse.Payer payer(PixPayer payer, boolean fullDocument) {
        if (payer.document() == null) {
            return new ReceivedPixResponse.Payer(null, null, payer.name());
        }
        String shown = fullDocument ? payer.document().value() : payer.document().masked();
        return payer.document() instanceof Cpf ? new ReceivedPixResponse.Payer(shown, null, payer.name())
                : new ReceivedPixResponse.Payer(null, shown, payer.name());
    }

    public ReceivedPixPageResponse toPage(PageResult<ReceivedPix> page, Function<ReceivedPix, List<Refund>> refunds, Instant from,
                                          Instant to, boolean fullDocument) {
        return new ReceivedPixPageResponse(new ReceivedPixPageResponse.Parameters(from, to,
                new ReceivedPixPageResponse.Pagination(page.page(), page.size(), page.totalPages(), page.totalElements())),
                page.content().stream().map(receivedPix -> toResponse(receivedPix, refunds.apply(receivedPix), fullDocument)).toList());
    }
}
