package br.com.pixmanager.shared.exception;

public class InvalidTransitionException extends BusinessException {

    public InvalidTransitionException(String code, String message) {
        super(code, message);
    }
}
