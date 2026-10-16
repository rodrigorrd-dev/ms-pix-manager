package br.com.pixmanager.core.domain.enums;

/** Motivos de exclusão aceitos pelo DICT (reconferir enum exato em docs/09). */
public enum DeletionReason {
    USER_REQUESTED, ACCOUNT_CLOSURE, BRANCH_TRANSFER, RECONCILIATION, FRAUD, DEFAULT_OPERATION
}
