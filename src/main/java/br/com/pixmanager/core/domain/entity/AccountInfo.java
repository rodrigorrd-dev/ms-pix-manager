package br.com.pixmanager.core.domain.entity;

import br.com.pixmanager.core.domain.enums.AccountStatus;
import br.com.pixmanager.core.domain.enums.HolderType;

import br.com.pixmanager.shared.model.document.Document;

import java.util.UUID;

/**
 * Conta + titular, somente leitura neste ms (a fonte da verdade é o core; o ms apenas referencia — decisão A2).
 * {@code documentHash} é o índice cego (HMAC) do documento do titular: escopo de unicidade do {@code txid}.
 */
public record AccountInfo(UUID id, UUID holderId, HolderType holderType, Document document, String documentHash,
                          String holderName, String ispb, String branch, String number, String accountType,
                          AccountStatus status) {

    public boolean isActive() {
        return status == AccountStatus.ACTIVE;
    }

    public boolean isIndividual() {
        return holderType == HolderType.INDIVIDUAL;
    }
}
