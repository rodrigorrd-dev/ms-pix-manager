package br.com.pixmanager.core.port.out;

/**
 * Faz o POST ao endpoint do recebedor. A implementação assina o corpo (HMAC + timestamp), não segue redirecionamentos e não lê o corpo da
 * resposta. Nunca lança por falha de rede: devolve o resultado, e o chamador decide o retry.
 */
public interface WebhookSenderPortOut {

    SendResult send(String url, String body);

    /** {@code httpStatus} nulo quando nem houve resposta (timeout, DNS, TLS). */
    record SendResult(Integer httpStatus, String error) {
        public boolean success() {
            return httpStatus != null && httpStatus >= 200 && httpStatus < 300;
        }
    }
}
