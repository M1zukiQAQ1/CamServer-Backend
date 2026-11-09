package edu.camserver.app.admin;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * PBKDF2-HMAC-SHA256 password hash in the form {@code pbkdf2-sha256$<iterations>$<salt>$<hash>}
 * (standard base64). {@code scripts/admin_password.py} writes the same format with Python's
 * {@code hashlib.pbkdf2_hmac}.
 */
public final class PasswordHash {
    private static final String SCHEME = "pbkdf2-sha256";

    private final int iterations;
    private final byte[] salt;
    private final byte[] hash;

    private PasswordHash(int iterations, byte[] salt, byte[] hash) {
        this.iterations = iterations;
        this.salt = salt;
        this.hash = hash;
    }

    public static PasswordHash parse(String encoded) {
        String[] parts = encoded.trim().split("\\$");
        if (parts.length != 4 || !SCHEME.equals(parts[0])) {
            throw new IllegalArgumentException("Expected " + SCHEME + "$<iterations>$<salt>$<hash>");
        }
        int iterations = Integer.parseInt(parts[1]);
        byte[] salt = Base64.getDecoder().decode(parts[2]);
        byte[] hash = Base64.getDecoder().decode(parts[3]);
        if (iterations < 100_000 || salt.length < 16 || hash.length < 32) {
            throw new IllegalArgumentException("Password hash is too weak (need >= 100000 iterations, 16-byte salt, 32-byte hash)");
        }
        return new PasswordHash(iterations, salt, hash);
    }

    public static String encode(char[] password, byte[] salt, int iterations) {
        return SCHEME + "$" + iterations + "$" + Base64.getEncoder().encodeToString(salt) + "$"
                + Base64.getEncoder().encodeToString(derive(password, salt, iterations, 32));
    }

    public boolean matches(char[] password) {
        return MessageDigest.isEqual(hash, derive(password, salt, iterations, hash.length));
    }

    private static byte[] derive(char[] password, byte[] salt, int iterations, int bytes) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, bytes * 8);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2WithHmacSHA256 unavailable", e);
        } finally {
            spec.clearPassword();
        }
    }
}
