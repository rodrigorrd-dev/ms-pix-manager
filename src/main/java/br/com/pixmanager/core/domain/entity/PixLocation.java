package br.com.pixmanager.core.domain.entity;

import br.com.pixmanager.core.domain.enums.ChargeType;

import java.util.UUID;

/** {@code loc}: URL do payload do QR dinâmico. {@code locationId} é o id numérico exposto na API. */
public record PixLocation(UUID id, long locationId, ChargeType type, String urlPath) {}
