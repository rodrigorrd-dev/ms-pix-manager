package br.com.pixmanager.infrastructure.persistence;

import br.com.pixmanager.support.integration.PostgresContainerSupport;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;

/** O changelog é o contrato do banco: aplica limpo, desfaz TODO changeset (cada um tem --rollback) e reaplica. */
@DisplayName("Liquibase — aplicar → rollback de todos os changesets → reaplicar")
class LiquibaseSchemaIT {

    private static final String MASTER = "db/changelog/db.changelog-master.yaml";

    @Test
    void shouldApplyRollbackEverythingAndApplyAgain() throws Exception {
        var pg = PostgresContainerSupport.POSTGRES;
        // banco PRÓPRIO: o schema compartilhado pertence aos ITs de Spring, e o rollback total o destruiria
        try (Connection admin = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
             var st = admin.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS liquibase_it");
            st.execute("CREATE DATABASE liquibase_it");
        }
        String url = pg.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/liquibase_it$1");
        try (Connection conn = DriverManager.getConnection(url, pg.getUsername(), pg.getPassword())) {
            Database database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(conn));
            try (Liquibase liquibase = new Liquibase(MASTER, new ClassLoaderResourceAccessor(), database)) {
                liquibase.update("");
                assertThat(count(conn, "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE' AND table_name NOT LIKE 'databasechangelog%'"))
                        .as("tabelas de domínio/infra criadas").isGreaterThanOrEqualTo(37);
                assertThat(count(conn, "SELECT count(*) FROM pg_matviews WHERE matviewname='mv_pix_reconciliation_daily'")).isEqualTo(1);

                int changesets = count(conn, "SELECT count(*) FROM databasechangelog");
                liquibase.rollback(changesets, "");
                assertThat(count(conn, "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_name='pix_charge'"))
                        .as("após rollback total").isZero();

                liquibase.update("");
                assertThat(count(conn, "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_name='pix_charge'"))
                        .isEqualTo(1);
            }
        }
    }

    private static int count(Connection conn, String sql) throws Exception {
        try (var st = conn.createStatement(); ResultSet resultSet = st.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }
}
