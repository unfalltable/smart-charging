package io.smartcharge.platform.shared.persistence;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.smartcharge.platform.DatabaseTestSupport;
import org.junit.jupiter.api.Test;

class RuntimeDatabaseRoleGuardIntegrationTest {
    @Test
    void acceptsTheRestrictedBusinessRoleAndRejectsMigrationOrSuperuserRole() throws Exception {
        var db = DatabaseTestSupport.database();
        assertThatCode(() -> new RuntimeDatabaseRoleGuard(db.runtimeJdbc().jdbc()).run(null)).doesNotThrowAnyException();
        assertThatThrownBy(() -> new RuntimeDatabaseRoleGuard(db.ownerJdbc()).run(null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("NOBYPASSRLS");
    }
}
