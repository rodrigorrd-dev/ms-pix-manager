package br.com.pixmanager.core.domain.enums;

/** Tipos de chave do DICT (API DICT 2.12.1). */
public enum PixKeyType {
    CPF, CNPJ, PHONE, EMAIL, EVP;

    /** PHONE/EMAIL exigem prova de posse (OTP) antes de ir ao DICT (BR-KEY-04). */
    public boolean requiresOwnershipProof() {
        return this == PHONE || this == EMAIL;
    }

    public boolean isDocumentKey() {
        return this == CPF || this == CNPJ;
    }
}
