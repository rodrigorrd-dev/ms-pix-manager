package br.com.pixmanager.core.port.out;

import java.util.UUID;

/** Gera e "assina" o código de prova de posse. A implementação usa fonte segura de aleatoriedade e HMAC (nunca texto puro no banco). */
public interface OtpCodePortOut {

    /** Código numérico de 6 dígitos (com zeros à esquerda). */
    String generate();

    /** HMAC do código ligado à chave (o mesmo código em outra chave gera outro hash). */
    String hash(UUID pixKeyId, String code);
}
