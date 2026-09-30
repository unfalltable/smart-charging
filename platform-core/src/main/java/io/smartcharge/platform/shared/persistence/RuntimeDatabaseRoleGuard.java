package io.smartcharge.platform.shared.persistence;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Profile("production")
@Order(0)
final class RuntimeDatabaseRoleGuard implements ApplicationRunner {
    private final JdbcTemplate jdbc;

    RuntimeDatabaseRoleGuard(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public void run(ApplicationArguments arguments) {
        boolean restricted = Boolean.TRUE.equals(jdbc.queryForObject("""
                select not exists(select 1 from pg_roles
                    where (rolsuper or rolbypassrls or rolcreaterole)
                      and pg_has_role(current_user,oid,'MEMBER'))
                """, Boolean.class));
        if (!restricted) throw new IllegalStateException(
                "Production database runtime identity must be NOSUPERUSER, NOBYPASSRLS, NOCREATEROLE and have no privileged role membership");
    }
}
