package io.smartcharge.platform;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assumptions;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import io.smartcharge.platform.tenancy.TenantJdbcExecutor;

/** Real database checks use an explicitly supplied isolated DB or a Docker test DB. */
public final class DatabaseTestSupport {
    private static Database database;

    private DatabaseTestSupport() { }

    public static synchronized Database database() throws SQLException {
        if (database != null) return database;
        String url = System.getenv("TEST_DATABASE_URL");
        String user = System.getenv("TEST_DATABASE_USER");
        String password = System.getenv("TEST_DATABASE_PASSWORD");
        if (url == null || url.isBlank()) {
            boolean dockerAvailable = DockerClientFactory.instance().isDockerAvailable();
            if (Boolean.parseBoolean(System.getenv("REQUIRE_DATABASE_TESTS")) && !dockerAvailable) {
                throw new IllegalStateException("Release verification requires Docker or an isolated TEST_DATABASE_URL");
            }
            Assumptions.assumeTrue(dockerAvailable, "Docker and TEST_DATABASE_URL are unavailable");
            PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
                    .withDatabaseName("charging_test").withUsername("migration_owner")
                    .withPassword("isolated-test-owner-password");
            postgres.start();
            Runtime.getRuntime().addShutdownHook(new Thread(postgres::stop));
            url = postgres.getJdbcUrl(); user = postgres.getUsername(); password = postgres.getPassword();
        }
        if (user == null || user.isBlank()) throw new IllegalStateException("TEST_DATABASE_USER is required");
        if (password == null) password = "";
        Flyway.configure().dataSource(url, user, password).locations("classpath:db/migration").load().migrate();
        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            connection.createStatement().execute("""
                    do $$ begin
                        if not exists(select 1 from pg_roles where rolname='app_runtime') then
                            create role app_runtime login password 'isolated-test-runtime-password' nosuperuser nobypassrls;
                        end if;
                    end $$
                    """);
            connection.createStatement().execute("grant usage on schema public to app_runtime");
            connection.createStatement().execute("grant select, insert, update, delete on all tables in schema public to app_runtime");
            connection.createStatement().execute("grant usage, select on all sequences in schema public to app_runtime");
        }
        database = new Database(url, user, password);
        return database;
    }

    public record Database(String url, String ownerUser, String ownerPassword) {
        public ChargingFixture seedCharging() {
            var jdbc = ownerJdbc();
            UUID tenant = UUID.randomUUID(), station = UUID.randomUUID(), device = UUID.randomUUID();
            UUID connector = UUID.randomUUID(), tariff = UUID.randomUUID(), customer = UUID.randomUUID();
            String deviceCode = "verification-" + device;
            jdbc.update("insert into tenant(id,code,display_name,status) values (?,?,'Verification','ACTIVE')",
                    tenant, "verification-" + tenant);
            jdbc.update("""
                    insert into station(id,tenant_id,organization_id,code,name,status)
                    select ?,?,id,'VERIFY','Verification station','ACTIVE' from operator_organization
                     where tenant_id=? and parent_id is null
                    """, station, tenant, tenant);
            jdbc.update("""
                    insert into device(id,tenant_id,station_id,device_code,protocol_code,product_model,
                                       connector_count,status,last_seen_at)
                    values (?,?,?,?,'SC1','Verification device',12,'ONLINE',now())
                    """, device, tenant, station, deviceCode);
            jdbc.update("""
                    insert into tariff(id,tenant_id,name,billing_mode,price_rules,effective_from,status)
                    values (?,?,'Verification tariff','DURATION',
                            '{"durationUnitPriceMinor":2,"energyUnitPriceMinor":0,"minimumAmountMinor":0}',
                            now()-interval '1 day','ACTIVE')
                    """, tariff, tenant);
            jdbc.update("""
                    insert into connector(id,tenant_id,device_id,connector_no,external_code,status,tariff_id)
                    values (?,?,?,1,?,'AVAILABLE',?)
                    """, connector, tenant, device, deviceCode + "-01", tariff);
            jdbc.update("insert into customer(id,tenant_id,status) values (?,?,'ACTIVE')", customer, tenant);
            return new ChargingFixture(tenant, station, device, deviceCode, connector, tariff, customer);
        }
        public Connection owner() throws SQLException {
            return DriverManager.getConnection(url, ownerUser, ownerPassword);
        }
        public Connection runtime() throws SQLException {
            return DriverManager.getConnection(url, "app_runtime", "isolated-test-runtime-password");
        }
        public JdbcTemplate ownerJdbc() {
            return new JdbcTemplate(new DriverManagerDataSource(url, ownerUser, ownerPassword));
        }
        public RuntimeJdbc runtimeJdbc() {
            var dataSource = new DriverManagerDataSource(url, "app_runtime", "isolated-test-runtime-password");
            var jdbc = new JdbcTemplate(dataSource);
            var transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
            return new RuntimeJdbc(jdbc, new TenantJdbcExecutor(jdbc, transactions), transactions);
        }
    }

    public record RuntimeJdbc(JdbcTemplate jdbc, TenantJdbcExecutor tenants, TransactionTemplate transactions) { }
    public record ChargingFixture(UUID tenantId, UUID stationId, UUID deviceId, String deviceCode,
                                  UUID connectorId, UUID tariffId, UUID customerId) { }
}
