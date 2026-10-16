package br.com.pixmanager.core.domain.entity;

import br.com.pixmanager.core.domain.enums.ComponentType;

/** Um componente do valor (original, saque, troco, multa, juros, abatimento, desconto). */
public record ValueComponent(ComponentType type, Money amount, String agentModality, String withdrawalProviderIspb) {

    public ValueComponent {
        if (amount.isNegative()) {
            throw new IllegalArgumentException("componente não pode ser negativo");
        }
    }
}
