package br.com.pixmanager.shared.constant;

/** Autoridades (escopo OAuth com prefixo {@code SCOPE_}) usadas fora da camada web. */
public final class ScopeNames {

    private ScopeNames() {}

    /** Libera CPF/CNPJ completo nas respostas (o padrão é mascarado; todo acesso completo é auditado). */
    public static final String PII_DOCUMENT_READ = "SCOPE_pii.document.read";
}
