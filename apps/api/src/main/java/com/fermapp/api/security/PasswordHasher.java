package com.fermapp.api.security;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * bcrypt with cost 12, compatible with the hashes bcryptjs produced. bcrypt only reads the
 * first 72 bytes of a password; bcryptjs truncated silently, so this class does the same
 * instead of letting Spring Security reject longer multi-byte passwords.
 */
@Component
public class PasswordHasher {

    private static final int ROUNDS = 12;
    private static final int MAX_BYTES = 72;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(ROUNDS);
    /** Comparing against a fake hash keeps the same response time for non-existent accounts. */
    private final String dummyHash = encoder.encode("fermapp-dummy-password");

    public String hash(String password) {
        return encoder.encode(truncate(password));
    }

    public boolean matches(String password, String hash) {
        return encoder.matches(truncate(password), hash == null ? dummyHash : hash);
    }

    private static String truncate(String password) {
        byte[] bytes = password.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_BYTES) {
            return password;
        }
        // Cut on a character boundary so the string stays valid UTF-8, as bcryptjs effectively did.
        int end = MAX_BYTES;
        while (end > 0 && (bytes[end] & 0xC0) == 0x80) {
            end--;
        }
        return new String(Arrays.copyOf(bytes, end), StandardCharsets.UTF_8);
    }
}
