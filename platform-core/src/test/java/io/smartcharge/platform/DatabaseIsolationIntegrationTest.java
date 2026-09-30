package io.smartcharge.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class DatabaseIsolationIntegrationTest {
    private static final UUID TENANT_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID TENANT_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    private static DatabaseTestSupport.Database database;

    @BeforeAll
    static void migrateAndSeed() throws SQLException {
        database = DatabaseTestSupport.database();
        try (Connection connection = owner()) {
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
                        'reconciliation_batch','settlement_statement','wallet_account','invoice_request',
                        'operator_organization')
                    """);
            assertThat(result.next()).isTrue();
            assertThat(result.getInt(1)).isEqualTo(9);
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
        return database.owner();
    }

    private static Connection runtime() throws SQLException {
        return database.runtime();
    }
}
