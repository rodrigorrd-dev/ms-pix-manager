package br.com.pixmanager.core.port.out;

/** Proteção contra SSRF: a URL do recebedor é entrada do usuário e o servidor vai chamá-la de DENTRO da nossa rede. */
public interface WebhookUrlPolicyPortOut {

    Verdict check(String url);

    sealed interface Verdict {
        record Allowed() implements Verdict {}

        /** Fora da política (esquema, credencial na URL, IP interno/loopback/metadata...). */
        record Forbidden(String reason) implements Verdict {}

        /** Nome não resolve em DNS. */
        record Unresolvable(String host) implements Verdict {}

        default boolean allowed() {
            return this instanceof Allowed;
        }

        default String describe() {
            return switch (this) {
                case Allowed allowed -> "ok";
                case Forbidden forbidden -> forbidden.reason();
                case Unresolvable unresolvable -> "o nome " + unresolvable.host() + " não resolve em DNS";
            };
        }
    }
}
