package br.com.pixmanager.api.rest.charge.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

/** Listagem no formato OFICIAL do BCB ({@code parametros.paginacao}); a API de gestão usa o envelope {@code PageResult}. */
public record ChargePageResponse(
        @JsonProperty("parametros") Parameters parameters,
        @JsonProperty("cobs") List<ChargeResponse> charges) {

    public record Parameters(
            @JsonProperty("inicio") Instant from,
            @JsonProperty("fim") Instant to,
            @JsonProperty("paginacao") Pagination pagination) {}

    public record Pagination(
            @JsonProperty("paginaAtual") int page,
            @JsonProperty("itensPorPagina") int pageSize,
            @JsonProperty("quantidadeDePaginas") int totalPages,
            @JsonProperty("quantidadeTotalDeItens") long totalItems) {}
}
