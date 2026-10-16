package br.com.pixmanager.core.port.in.pixkey;

public interface ActivatePendingKeysUseCase {

    /** Processa chaves PENDING_DICT: ativa, rejeita (negócio) ou deixa para a próxima rodada (técnico). Retorna ativadas. */
    int activatePending(int limit);
}
