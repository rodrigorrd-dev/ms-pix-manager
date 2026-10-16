package br.com.pixmanager.core.port.in.charge;

import br.com.pixmanager.core.domain.entity.Charge;

/** Cobrança + valor (decifrado) da chave do recebedor, para a resposta {@code chave}. */
public record ChargeDetails(Charge charge, String pixKey) {}
