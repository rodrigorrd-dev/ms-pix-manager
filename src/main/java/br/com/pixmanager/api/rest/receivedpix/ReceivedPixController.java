package br.com.pixmanager.api.rest.receivedpix;

import br.com.pixmanager.api.rest.receivedpix.dto.response.ReceivedPixPageResponse;
import br.com.pixmanager.api.rest.receivedpix.dto.response.ReceivedPixResponse;
import br.com.pixmanager.shared.security.ScopeAuthorization;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Instant;

/** Pix recebidos — API Pix do BCB 2.10.0. Somente leitura: o Pix nasce do evento do SPI, nunca por esta API. */
@RequestMapping("/api/pix/v2/pix")
@Validated
public interface ReceivedPixController {

    @GetMapping("/{e2eid}")
    @PreAuthorize(ScopeAuthorization.PIX_READ)
    ResponseEntity<ReceivedPixResponse> find(@PathVariable("e2eid") String endToEndId);

    @GetMapping
    @PreAuthorize(ScopeAuthorization.PIX_READ)
    ResponseEntity<ReceivedPixPageResponse> search(
            @RequestParam("inicio") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam("fim") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(name = "txid", required = false) String txid,
            @RequestParam(name = "cpf", required = false) String cpf,
            @RequestParam(name = "cnpj", required = false) String cnpj,
            @RequestParam(name = "paginacao.paginaAtual", defaultValue = "0") @Min(0) int page,
            @RequestParam(name = "paginacao.itensPorPagina", defaultValue = "100") @Min(1) @Max(100) int pageSize);
}
