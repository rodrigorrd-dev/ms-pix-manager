package br.com.pixmanager.api.rest.receivedpix.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

public record ReceivedPixPageResponse(
        @JsonProperty("parametros") Parameters parameters,
        @JsonProperty("pix") List<ReceivedPixResponse> pix) {

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
