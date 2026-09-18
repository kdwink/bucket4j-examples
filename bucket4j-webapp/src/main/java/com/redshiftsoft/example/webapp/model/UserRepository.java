package com.redshiftsoft.example.webapp.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory user store pre-loaded with 100 test users (test-user-00 through test-user-99).
 * All share password "abcd1234". Organization IDs are assigned by integer division (i / 10):
 *
 * <pre>
 *   test-user-00 .. test-user-09  →  organizationId 0
 *   test-user-10 .. test-user-19  →  organizationId 1
 *   test-user-20 .. test-user-29  →  organizationId 2
 *   test-user-30 .. test-user-39  →  organizationId 3
 *   test-user-40 .. test-user-49  →  organizationId 4
 *   test-user-50 .. test-user-59  →  organizationId 5
 *   test-user-60 .. test-user-69  →  organizationId 6
 *   test-user-70 .. test-user-79  →  organizationId 7
 *   test-user-80 .. test-user-89  →  organizationId 8
 *   test-user-90 .. test-user-99  →  organizationId 9
 * </pre>
 */
public class UserRepository {

    private final Map<String, User> usersByUsername = new LinkedHashMap<>();

    public UserRepository() {
        String passwordHash = sha256("abcd1234");
        for (int i = 0; i <= 99; i++) {
            String username = String.format("test-user-%02d", i);
            String lastName = "User " + i;
            User user = new User(i, username, passwordHash, "Test", lastName, i / 10);
            usersByUsername.put(username, user);
        }
    }

    public List<User> findAll() {
        return List.copyOf(usersByUsername.values());
    }

    public Optional<User> findByUsername(String username) {
        return Optional.ofNullable(usersByUsername.get(username));
    }

    public Optional<User> findByUserId(long userId) {
        return usersByUsername.values().stream()
                .filter(user -> user.userId() == userId)
                .findFirst();
    }

    public Optional<User> authenticate(String username, String password) {
        return findByUsername(username)
                .filter(user -> user.passwordHash().equals(sha256(password)));
    }

    static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

}
