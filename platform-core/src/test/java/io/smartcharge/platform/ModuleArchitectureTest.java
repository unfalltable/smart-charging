package io.smartcharge.platform;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModuleArchitectureTest {
    @Test
    void moduleBoundariesRemainValid() {
        ApplicationModules.of(PlatformCoreApplication.class).verify();
    }
}
