package io.smartcharge.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import org.junit.jupiter.api.Test;

class TemporaryPasswordGeneratorTest {
    @Test
    void generatesUniquePasswordsThatMeetTheFirstLoginPolicy() {
        TemporaryPasswordGenerator generator = new TemporaryPasswordGenerator();
        HashSet<String> generated = new HashSet<>();

        for (int index = 0; index < 100; index++) {
            String password = generator.generate();
            assertThat(password).hasSize(20)
                    .matches(".*[A-Z].*")
                    .matches(".*[a-z].*")
                    .matches(".*[0-9].*")
                    .matches(".*[^A-Za-z0-9].*");
            assertThat(generated.add(password)).isTrue();
        }
    }
}
