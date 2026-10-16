package br.com.pixmanager.shared.exception;

/** Requisição malformada (400): header inválido, parâmetro fora de faixa. Diferente de {@link BusinessException} (422). */
public class BadRequestException extends RuntimeException {

    private final String code;

    public BadRequestException(String code, String message) {
        // desfecho ESPERADO (404/409/422/400, recusa do DICT): sem stack trace, que ninguém lê e custa a cada ocorrência
        super(message, null, false, false);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
