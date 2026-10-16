package br.com.pixmanager.core.port.out.persistence;

import br.com.pixmanager.core.domain.enums.ChargeType;
import br.com.pixmanager.core.domain.entity.PixLocation;

public interface LocationPortOut {

    /** Cria o {@code loc} (id numérico via sequência + token de URL aleatório) na transação corrente. */
    PixLocation create(ChargeType type);
}
