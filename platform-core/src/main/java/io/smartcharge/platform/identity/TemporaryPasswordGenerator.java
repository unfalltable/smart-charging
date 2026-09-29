package io.smartcharge.platform.identity;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
final class TemporaryPasswordGenerator {
    private static final char[] CHARS =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%&*+-_".toCharArray();
    private final SecureRandom random = new SecureRandom();

    String generate() {
        StringBuilder password = new StringBuilder("Aa7!");
        while (password.length() < 20) password.append(CHARS[random.nextInt(CHARS.length)]);
        List<Character> shuffled = new ArrayList<>();
        password.chars().mapToObj(value -> (char) value).forEach(shuffled::add);
        Collections.shuffle(shuffled, random);
        StringBuilder result = new StringBuilder(20);
        shuffled.forEach(result::append);
        return result.toString();
    }
}
