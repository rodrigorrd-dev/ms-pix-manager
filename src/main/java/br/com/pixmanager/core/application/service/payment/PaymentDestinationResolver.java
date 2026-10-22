package br.com.pixmanager.core.application.service.payment;

import br.com.pixmanager.core.port.out.DynamicQrPayloadPortOut;
import br.com.pixmanager.core.domain.entity.BrCode;
import br.com.pixmanager.core.domain.entity.Caller;
import br.com.pixmanager.core.domain.entity.Money;
import br.com.pixmanager.core.domain.entity.PaymentDestination;
import br.com.pixmanager.core.domain.entity.PixKeyValue;
import br.com.pixmanager.core.domain.enums.PixKeyType;
import br.com.pixmanager.core.port.in.payment.CreatePaymentOrderUseCase.CreatePaymentOrderCommand;
import br.com.pixmanager.core.port.in.pixkey.LookupExternalKeyUseCase;
import br.com.pixmanager.shared.constant.ErrorCodes;
import br.com.pixmanager.shared.exception.BadRequestException;
import br.com.pixmanager.shared.exception.BusinessException;
import br.com.pixmanager.shared.model.document.Document;

import java.util.Optional;

/** Resolve o favorecido do pagamento conforme o método (chave, QR estático, manual); a consulta ao DICT é HTTP e roda FORA de transação. */
class PaymentDestinationResolver {

    private final LookupExternalKeyUseCase lookup;
    private final DynamicQrPayloadPortOut dynamicQr;
    private final java.time.Clock clock;

    PaymentDestinationResolver(LookupExternalKeyUseCase lookup, DynamicQrPayloadPortOut dynamicQr, java.time.Clock clock) {
        this.lookup = lookup;
        this.dynamicQr = dynamicQr;
        this.clock = clock;
    }

    record Resolved(PaymentDestination destination, Money amount, String qrTxid) {}

    Resolved resolve(Caller caller, CreatePaymentOrderCommand command) {
        return switch (command.method()) {
            case KEY -> byKey(caller, command, command.pixKeyType(), command.pixKey(), command.amount(), null);
            case QR_STATIC -> {
                BrCode brCode = BrCode.parse(command.qrPayload());
                if (brCode.isDynamic() || brCode.pixKey().isEmpty()) {
                    throw new BusinessException(ErrorCodes.INVALID_PAYLOAD, "O BR Code informado não é um QR estático com chave.");
                }
                PixKeyValue key = PixKeyValue.infer(brCode.pixKey().get())
                        .orElseThrow(() -> new BusinessException(ErrorCodes.INVALID_PIX_KEY, "A chave do BR Code é inválida."));
                Money amount = amountFor(command.amount(), brCode.amount());
                yield byKey(caller, command, key.type(), key.value(), amount, brCode.txid().orElse(null));
            }
            case QR_DYNAMIC -> dynamic(caller, command);
            case MANUAL -> manual(command);
        };
    }

    private Resolved byKey(Caller caller, CreatePaymentOrderCommand command, PixKeyType type, String rawKey, Money amount, String qrTxid) {
        if (type == null || rawKey == null || rawKey.isBlank()) {
            throw new BadRequestException(ErrorCodes.INVALID_PAYLOAD, "O pagamento por chave exige tipo e valor da chave.");
        }
        if (amount == null) {
            throw new BadRequestException(ErrorCodes.INVALID_PAYLOAD, "O valor do pagamento é obrigatório.");
        }
        var entry = lookup.lookup(caller, command.payerAccountId(), type, rawKey)
                .orElseThrow(() -> new BusinessException(ErrorCodes.INVALID_PIX_KEY, "Chave Pix não encontrada."));
        if (command.documentHint() != null && !command.documentHint().isBlank()) {
            Document expected = Document.of(command.documentHint());                          // BR-DOC: dígito verificador
            if (!expected.value().equals(entry.ownerDocument().value())) {
                throw new BusinessException(ErrorCodes.DESTINATION_MISMATCH, "O documento do favorecido não confere com o da chave.");
            }
        }
        return new Resolved(new PaymentDestination(type, rawKey, entry.ownerDocument(), entry.ownerName(), entry.ispb(), entry.branch(),
                entry.accountNumber(), entry.accountType()), amount, qrTxid);
    }

    /** QR dinâmico: o valor e a chave vêm do payload ASSINADO do recebedor, não do que o cliente digitou. */
    private Resolved dynamic(Caller caller, CreatePaymentOrderCommand command) {
        BrCode brCode = BrCode.parse(command.qrPayload());
        if (!brCode.isDynamic()) {
            throw new BusinessException(ErrorCodes.INVALID_PAYLOAD, "O BR Code informado não é um QR dinâmico.");
        }
        var payload = dynamicQr.fetch(brCode.paymentUrl().get());
        if (!"ATIVA".equals(payload.status())) {
            throw new BusinessException(ErrorCodes.QR_DYNAMIC_INVALID, "A cobrança do QR não está ativa (status " + payload.status() + ").");
        }
        if (payload.expiresAt() != null && !clock.instant().isBefore(payload.expiresAt())) {
            throw new BusinessException(ErrorCodes.QR_DYNAMIC_INVALID, "A cobrança do QR está vencida.");
        }
        if (command.amount() != null && !command.amount().equals(payload.amount())) {
            throw new BusinessException(ErrorCodes.INVALID_PAYLOAD, "O valor informado difere do valor da cobrança.");
        }
        PixKeyValue key = PixKeyValue.infer(payload.pixKey())
                .orElseThrow(() -> new BusinessException(ErrorCodes.QR_DYNAMIC_INVALID, "A chave da cobrança é inválida."));
        return byKey(caller, command, key.type(), key.value(), payload.amount(), payload.txid());
    }

    private Resolved manual(CreatePaymentOrderCommand command) {
        if (command.ispb() == null || !command.ispb().matches("\\d{8}") || command.branch() == null || command.account() == null || command.accountType() == null
                || command.name() == null || command.amount() == null) {
            throw new BadRequestException(ErrorCodes.INVALID_PAYLOAD, "Pagamento manual exige ispb, agência, conta, tipo, nome e valor.");
        }
        Document document = command.document() == null ? null : Document.of(command.document());   // CPF/CNPJ com dígito verificador
        return new Resolved(new PaymentDestination(null, null, document, command.name(), command.ispb(), command.branch(), command.account(), command.accountType()),
                command.amount(), null);
    }

    /** QR com valor fixo: o valor informado, se houver, precisa ser igual; sem valor no QR, o informado é obrigatório. */
    private static Money amountFor(Money informed, Optional<Money> inQr) {
        if (inQr.isPresent()) {
            if (informed != null && !informed.equals(inQr.get())) {
                throw new BusinessException(ErrorCodes.INVALID_PAYLOAD, "O valor informado difere do valor do BR Code.");
            }
            return inQr.get();
        }
        return informed;
    }
}
