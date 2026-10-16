-- Executado uma única vez pelo postgres (docker-entrypoint-initdb.d). O schema vem do Liquibase, nunca daqui.
SELECT 'database pixmanager criado pelo POSTGRES_DB' AS info;
