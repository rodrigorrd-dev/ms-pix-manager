package br.com.pixmanager.core.port.in.payment;

import br.com.pixmanager.core.domain.entity.BrCode;

public interface DecodeBrCodeUseCase {

    /** Decodifica (e valida o CRC de) um BR Code "copia e cola" sem criar ordem. */
    BrCode decode(String payload);
}
