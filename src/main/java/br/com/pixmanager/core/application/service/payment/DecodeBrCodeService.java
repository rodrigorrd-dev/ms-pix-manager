package br.com.pixmanager.core.application.service.payment;

import br.com.pixmanager.core.domain.entity.BrCode;
import br.com.pixmanager.core.port.in.payment.DecodeBrCodeUseCase;

public class DecodeBrCodeService implements DecodeBrCodeUseCase {

    @Override
    public BrCode decode(String payload) {
        return BrCode.parse(payload);
    }
}
