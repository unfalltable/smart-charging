package io.smartcharge.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class DatabaseIsolationIntegrationTest {
    private static final UUID TENANT_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID TENANT_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("charging_test")
            .withUsername("migration_owner")
            .withPassword("migration-password");

    @BeforeAll
    static void migrateAndSeed() throws SQLException {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load().migrate();
        try (Connection connection = owner()) {
            connection.createStatement().execute("create role app_runtime login password 'runtime-password' nosuperuser nobypassrls");
            connection.createStatement().execute("grant usage on schema public to app_runtime");
            connection.createStatement().execute("grant select, insert, update, delete on all tables in schema public to app_runtime");
            connection.createStatement().execute("grant usage, select on all sequences in schema public to app_runtime");
            connection.createStatement().execute("insert into tenant(id, code, display_name, status) values "
                    + "('" + TENANT_A + "','tenant-a','Tenant A','ACTIVE'),"
                    + "('" + TENANT_B + "','tenant-b','Tenant B','ACTIVE')");
            connection.createStatement().execute("insert into station(id, tenant_id, code, name, status) values "
                    + "('aaaaaaaa-0000-0000-0000-000000000001','" + TENANT_A + "','A-1','Station A','ACTIVE'),"
                    + "('bbbbbbbb-0000-0000-0000-000000000001','" + TENANT_B + "','B-1','Station B','ACTIVE')");
        }
    }

    @Test
    void migrationsCreateCommercialTables() throws SQLException {
        try (Connection connection = owner(); var statement = connection.createStatement()) {
            var result = statement.executeQuery("""
                    select count(*) from information_schema.tables
                     where table_schema='public' and table_name in
                       ('charging_order','payment_transaction','device_alarm','work_order',
                        'reconciliation_batch','settlement_statement','wallet_account','invoice_request')
                    """);
            assertThat(result.next()).isTrue();
            assertThat(result.getInt(1)).isEqualTo(8);
        }
    }

    @Test
    void runtimeRoleCanOnlySeeSelectedTenant() throws SQLException {
        try (Connection connection = runtime()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("select set_config('app.tenant_id', ?, true)")) {
                statement.setString(1, TENANT_A.toString());
                statement.execute();
            }
            try (var result = connection.createStatement().executeQuery("select code from station order by code")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualTo("A-1");
                assertThat(result.next()).isFalse();
            }
            connection.rollback();
        }
    }

    @Test
    void runtimeRoleCannotInsertAnotherTenantsRow() throws SQLException {
        try (Connection connection = runtime()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("select set_config('app.tenant_id', ?, true)")) {
                statement.setString(1, TENANT_A.toString());
                statement.execute();
            }
            assertThatThrownBy(() -> connection.createStatement().execute("""
                    insert into station(id, tenant_id, code, name, status)
                    values ('bbbbbbbb-0000-0000-0000-000000000002',
                            'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb','B-2','Forbidden','ACTIVE')
                    """)).isInstanceOf(SQLException.class);
            connection.rollback();
        }
    }

    private static Connection owner() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static Connection runtime() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), "app_runtime", "runtime-password");
    }
}
