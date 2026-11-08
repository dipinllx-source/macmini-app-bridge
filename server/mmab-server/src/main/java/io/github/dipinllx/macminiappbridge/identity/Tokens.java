package io.github.dipinllx.macminiappbridge.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** 随机令牌与 hash。服务端只保存 SHA-256(token)，不保存明文会话 / 票据（设计 §6.1、§10.1）。 */
public final class Tokens {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Tokens() {
    }

    /** 256 bit 随机值，base64url 无填充。 */
    public static String random() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** 短随机 ID（审计 / 租约 / 连接标识，不是凭据）。 */
    public static String shortId(String prefix) {
        byte[] b = new byte[9];
        RANDOM.nextBytes(b);
        return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(b).replace('_', 'x').replace('-', 'y');
    }

    public static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    /** 令牌格式粗检：只接受 base64url 字符与合理长度，避免把任意头部内容拿去 hash 查表。 */
    public static boolean looksLikeToken(String value) {
        return value != null && value.length() >= 32 && value.length() <= 128 && value.matches("[A-Za-z0-9_-]+");
    }
}
