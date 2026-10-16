package br.com.pixmanager.core.domain.enums;

/** Escopos de limite (BR-LIM): por transação, diário, noturno (PF), novo favorecido e dispositivo não cadastrado. */
public enum LimitScope {
    PER_TRANSACTION, DAILY, NIGHTLY, NEW_PAYEE, DEVICE_UNREGISTERED_PER_TX, DEVICE_UNREGISTERED_DAILY
}
