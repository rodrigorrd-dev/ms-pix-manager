package br.com.pixmanager.shared.model.document;

import br.com.pixmanager.shared.exception.InvalidDocumentException;

import java.util.Optional;

/**
 * Documento do titular/devedor/pagador: {@link Cpf} (PF) ou {@link Cnpj} (PJ). "Document" é o conceito neste ms (não há
 * anexos, então não colide com "arquivo"); CPF/CNPJ ficam literais por serem termos regulatórios (docs/11).
 */
public sealed interface Document permits Cpf, Cnpj {

    /** Valor normalizado (apenas dígitos/letras maiúsculas). Sensível: não logar. */
    String value();

    /** {@code CPF} ou {@code CNPJ}. */
    String type();

    String masked();

    /** Máscara de um documento opcional ({@code null} quando ausente). */
    static String maskedOrNull(Document document) {
        return document == null ? null : document.masked();
    }

    /** 11 caracteres numéricos = CPF; 14 = CNPJ. Devolve vazio se nenhum dos dois for válido. */
    static Optional<Document> tryParse(String raw) {
        Optional<Cpf> cpf = Cpf.tryParse(raw);
        if (cpf.isPresent()) {
            return Optional.of(cpf.get());
        }
        return Cnpj.tryParse(raw).map(cnpj -> cnpj);
    }

    static Document of(String raw) {
        return tryParse(raw).orElseThrow(InvalidDocumentException::document);
    }

    default boolean isIndividual() {
        return this instanceof Cpf;
    }
}
