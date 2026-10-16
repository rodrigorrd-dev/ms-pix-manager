package br.com.pixmanager.core.domain.entity;

import br.com.pixmanager.shared.model.document.Document;

/** Pagador de um Pix recebido. {@code document} é opcional: documento inválido/mascarado do SPI NÃO pode rejeitar um Pix já liquidado. */
public record PixPayer(Document document, String name, String ispb, String branch, String account, String accountType) {}
