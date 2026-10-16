package br.com.pixmanager.api.rest.limit.dto.response;

import br.com.pixmanager.core.domain.enums.LimitScope;

import java.time.Instant;

/** {@code pendingAmount}/{@code pendingEffectiveAt}: aumento em carência (vale após 24–48 h); {@code isDefault}: ainda não configurado. */
public record LimitResponse(LimitScope scope, String amount, String ceilingAmount, String pendingAmount, Instant pendingEffectiveAt,
                            boolean isDefault) {}
