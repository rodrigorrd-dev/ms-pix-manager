package br.com.pixmanager.api.rest.webhook.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

/** Envelope BCB da listagem de webhooks ({@code parametros} + {@code webhooks}). */
public record WebhookPageResponse(@JsonProperty("parametros") Parameters parameters, @JsonProperty("webhooks") List<WebhookResponse> webhooks) {

    public record Parameters(@JsonProperty("inicio") Instant from, @JsonProperty("fim") Instant to,
                             @JsonProperty("paginacao") Pagination pagination) {}

    public record Pagination(@JsonProperty("paginaAtual") int page, @JsonProperty("itensPorPagina") int pageSize,
                             @JsonProperty("quantidadeDePaginas") int totalPages, @JsonProperty("quantidadeTotalDeItens") long totalItems) {}
}
