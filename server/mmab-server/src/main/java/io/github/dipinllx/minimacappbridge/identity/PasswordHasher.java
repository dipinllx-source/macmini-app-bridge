package io.github.dipinllx.minimacappbridge.identity;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * 操作者口令 hash：{@code pbkdf2-sha256$<iterations>$<salt b64>$<hash b64>}（设计 §6.1：只存盐化强 hash）。
 * 迭代次数下限 100000；生产建议 600000（OWASP 2023 对 PBKDF2-HMAC-SHA256 的建议值）。
 */
public final class PasswordHasher {

    public static final int DEFAULT_ITERATIONS = 600_000;
    public static final int MIN_ITERATIONS = 100_000;
    private static final String PREFIX = "pbkdf2-sha256";
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordHasher() {
    }

    public static String hash(char[] password, int iterations) {
        if (iterations < MIN_ITERATIONS) {
            throw new IllegalArgumentException("iterations below minimum");
        }
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        byte[] dk = derive(password, salt, iterations);
        return PREFIX + "$" + iterations + "$" + Base64.getEncoder().encodeToString(salt) + "$"
                + Base64.getEncoder().encodeToString(dk);
    }

    public static boolean verify(char[] password, String encoded) {
        if (password == null || encoded == null) {
            return false;
        }
        String[] parts = encoded.split("\\$", -1);
        if (parts.length != 4 || !PREFIX.equals(parts[0])) {
            return false;
        }
        int iterations;
        byte[] salt;
        byte[] expected;
        try {
            iterations = Integer.parseInt(parts[1]);
            salt = Base64.getDecoder().decode(parts[2]);
            expected = Base64.getDecoder().decode(parts[3]);
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (iterations < MIN_ITERATIONS || salt.length < 16 || expected.length < 32) {
            return false;
        }
        byte[] actual = derive(password, salt, iterations);
        try {
            return MessageDigest.isEqual(actual, expected);
        } finally {
            Arrays.fill(actual, (byte) 0);
        }
    }

    /** 配置校验：格式与参数是否可用（不验证口令）。 */
    public static boolean isWellFormed(String encoded) {
        if (encoded == null) {
            return false;
        }
        String[] parts = encoded.split("\\$", -1);
        try {
            return parts.length == 4 && PREFIX.equals(parts[0]) && Integer.parseInt(parts[1]) >= MIN_ITERATIONS
                    && Base64.getDecoder().decode(parts[2]).length >= 16
                    && Base64.getDecoder().decode(parts[3]).length >= 32;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static byte[] derive(char[] password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2WithHmacSHA256 unavailable", e);
        } finally {
            spec.clearPassword();
        }
    }
}
