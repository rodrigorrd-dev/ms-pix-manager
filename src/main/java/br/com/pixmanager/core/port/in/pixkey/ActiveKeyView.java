package br.com.pixmanager.core.port.in.pixkey;

import java.util.UUID;

/** Visão mínima de uma chave ATIVA para outros módulos (cobrança): o módulo da chave não expõe o agregado. */
public record ActiveKeyView(UUID keyId, UUID accountId, String holderDocumentHash, String keyMasked) {}
