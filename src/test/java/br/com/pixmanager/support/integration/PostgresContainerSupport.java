package br.com.pixmanager.support.integration;

import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Container Postgres SINGLETON por JVM (iniciado uma vez e reaproveitado por todos os ITs — padrão do mesa-credito-full).
 * {@code wal_level=logical}: o changelog cria a publication do Debezium. Postgres real, nunca H2: constraints parciais,
 * {@code jsonb}, {@code bytea} e {@code ON CONFLICT} só existem aqui.
 */
public final class PostgresContainerSupport {

    public static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("pixmanager").withUsername("pix").withPassword("pix")
            .withCommand("postgres", "-c", "wal_level=logical");

    static {
        POSTGRES.start();
    }

    private PostgresContainerSupport() {}
}
