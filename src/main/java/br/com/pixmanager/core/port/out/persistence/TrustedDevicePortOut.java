package br.com.pixmanager.core.port.out.persistence;

import java.util.UUID;

/** Dispositivos cadastrados do cliente. O identificador bruto nunca é gravado: só o índice cego (HMAC). */
public interface TrustedDevicePortOut {

    boolean isRegistered(UUID accountId, String deviceId);
}
