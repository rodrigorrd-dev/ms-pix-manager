package br.com.pixmanager.shared.exception;

/**
 * Falha de cifragem/decifragem/índice cego: é erro TÉCNICO do serviço (chave, versão, dado corrompido), nunca regra de negócio.
 * Estende {@link IllegalStateException} por compatibilidade, mas o {@link ExceptionClassifier} a trata como 500 (não 422).
 */
public class CryptoException extends IllegalStateException {

    public CryptoException(String message) {
        super(message);
    }

    public CryptoException(String message, Throwable cause) {
        super(message, cause);
    }
}
