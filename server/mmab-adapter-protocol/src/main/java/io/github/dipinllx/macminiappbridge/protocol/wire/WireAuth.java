package io.github.dipinllx.macminiappbridge.protocol.wire;

import io.github.dipinllx.macminiappbridge.protocol.Envelope;
import io.github.dipinllx.macminiappbridge.protocol.EnvelopeJson;
import io.github.dipinllx.macminiappbridge.protocol.ErrorCode;
import io.github.dipinllx.macminiappbridge.protocol.ProtocolException;
import io.github.dipinllx.macminiappbridge.protocol.StrictJson;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 平台 ↔ 适配器内部认证（设计 §6.2）。规范见 {@code sdk/protocol/adapter-wire-v1.md}，
 * Node 实现见 {@code sdk/driver-node/wire.mjs}。
 *
 * <ol>
 *   <li>平台为每次 adapter 启动生成 32 字节随机服务密钥，经 stdin 传入；不进 argv / 环境变量 / 日志。</li>
 *   <li>adapter 只监听 127.0.0.1，连接建立后发 hello（含一次性 adapterNonce）。</li>
 *   <li>平台回 auth：clientNonce + HMAC(key, 标签|platform|两个 nonce|instanceId|generation|protocol)。</li>
 *   <li>adapter 校验后回 auth-ok，附反向 HMAC（互相认证）；否则发 error 并断开。</li>
 *   <li>此后每帧 = {@code hex(HMAC(sessionKey, 方向\njson)) + " " + json}，json 内 {@code n} 严格 +1，
 *       由双方 nonce 派生 sessionKey，跨连接 / 反射 / 重放 / 乱序均被拒。</li>
 * </ol>
 */
public final class WireAuth {

    public static final String AUTH_LABEL = "mmab-auth-v1";
    public static final String SESSION_LABEL = "mmab-session-v1";
    public static final String PLATFORM_TO_ADAPTER = "p2a";
    public static final String ADAPTER_TO_PLATFORM = "a2p";
    public static final int KEY_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    private WireAuth() {
    }

    public static byte[] newServiceKey() {
        byte[] key = new byte[KEY_BYTES];
        RANDOM.nextBytes(key);
        return key;
    }

    public static String newNonce() {
        byte[] n = new byte[32];
        RANDOM.nextBytes(n);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(n);
    }

    public static String platformProof(byte[] serviceKey, String adapterNonce, String clientNonce,
            String instanceId, long generation, String protocol) {
        return hex(hmac(serviceKey, String.join("\n", AUTH_LABEL, "platform", adapterNonce, clientNonce,
                instanceId, Long.toString(generation), protocol)));
    }

    public static String adapterProof(byte[] serviceKey, String adapterNonce, String clientNonce,
            String instanceId, long generation, String protocol) {
        return hex(hmac(serviceKey, String.join("\n", AUTH_LABEL, "adapter", adapterNonce, clientNonce,
                instanceId, Long.toString(generation), protocol)));
    }

    public static byte[] sessionKey(byte[] serviceKey, String adapterNonce, String clientNonce) {
        return hmac(serviceKey, String.join("\n", SESSION_LABEL, adapterNonce, clientNonce));
    }

    public static String frameMac(byte[] sessionKey, String direction, String json) {
        return hex(hmac(sessionKey, direction + "\n" + json));
    }

    /** 编码一帧：{@code mac + " " + {"n":n,"env":{...}}}。 */
    public static String encodeFrame(byte[] sessionKey, String direction, long n, Envelope envelope) {
        ObjectNode frame = StrictJson.WIRE.object();
        frame.put("n", n);
        frame.set("env", EnvelopeJson.toNode(envelope));
        String json = StrictJson.WIRE.write(frame);
        return frameMac(sessionKey, direction, json) + " " + json;
    }

    /**
     * 解码并校验一帧。MAC 不符或序号不是 {@code expectedN} 视为重放 / 篡改（{@link ErrorCode#REPLAY_REJECTED}），
     * 调用方必须断开连接。
     */
    public static Envelope decodeFrame(byte[] sessionKey, String direction, long expectedN, String line) {
        int sp = line.indexOf(' ');
        if (sp != 64) {
            throw new ProtocolException(ErrorCode.MALFORMED_FRAME, "frame must start with 64 hex mac");
        }
        String mac = line.substring(0, sp);
        String json = line.substring(sp + 1);
        if (!constantTimeEquals(mac, frameMac(sessionKey, direction, json))) {
            throw new ProtocolException(ErrorCode.REPLAY_REJECTED, "frame mac mismatch");
        }
        JsonNode frame = StrictJson.WIRE.parse(json);
        if (!frame.isObject() || frame.size() != 2 || frame.get("n") == null || frame.get("env") == null
                || !frame.get("n").isIntegralNumber()) {
            throw new ProtocolException(ErrorCode.MALFORMED_FRAME, "frame shape");
        }
        if (frame.get("n").longValue() != expectedN) {
            throw new ProtocolException(ErrorCode.REPLAY_REJECTED, "frame counter out of order");
        }
        return EnvelopeJson.fromNode(frame.get("env"));
    }

    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    static byte[] hmac(byte[] key, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    static String hex(byte[] bytes) {
        return HEX.formatHex(bytes);
    }
}
