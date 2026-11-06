package io.github.dipinllx.minimacappbridge.protocol.wire;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dipinllx.minimacappbridge.protocol.Envelope;
import io.github.dipinllx.minimacappbridge.protocol.ErrorCode;
import io.github.dipinllx.minimacappbridge.protocol.MessageKind;
import io.github.dipinllx.minimacappbridge.protocol.ProtocolException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class WireAuthTest {

    private final byte[] key = WireAuth.newServiceKey();
    private final String an = WireAuth.newNonce();
    private final String cn = WireAuth.newNonce();
    private final byte[] session = WireAuth.sessionKey(key, an, cn);

    private static Envelope env() {
        return Envelope.builder().profileId("mock/0.1.0").instanceId("mock-01").processGeneration(1)
                .messageKind(MessageKind.CONTROL).operation("mmab.fence")
                .deadline(Instant.parse("2026-11-07T00:00:30Z")).build();
    }

    private static ErrorCode codeOf(Runnable r) {
        try {
            r.run();
        } catch (ProtocolException e) {
            return e.code();
        }
        return null;
    }

    @Test
    void proofsBindNoncesInstanceGenerationAndDirection() {
        String p = WireAuth.platformProof(key, an, cn, "mock-01", 1, "1.0");
        assertThat(p).hasSize(64);
        assertThat(WireAuth.adapterProof(key, an, cn, "mock-01", 1, "1.0")).isNotEqualTo(p);
        assertThat(WireAuth.platformProof(key, WireAuth.newNonce(), cn, "mock-01", 1, "1.0")).isNotEqualTo(p);
        assertThat(WireAuth.platformProof(key, an, cn, "mock-02", 1, "1.0")).isNotEqualTo(p);
        assertThat(WireAuth.platformProof(key, an, cn, "mock-01", 2, "1.0")).isNotEqualTo(p);
        assertThat(WireAuth.platformProof(WireAuth.newServiceKey(), an, cn, "mock-01", 1, "1.0")).isNotEqualTo(p);
    }

    @Test
    void frameRoundTrip() {
        String line = WireAuth.encodeFrame(session, WireAuth.PLATFORM_TO_ADAPTER, 1, env());
        assertThat(line).doesNotContain("\n");
        assertThat(WireAuth.decodeFrame(session, WireAuth.PLATFORM_TO_ADAPTER, 1, line)).isEqualTo(env());
    }

    @Test
    void replayReorderReflectionTamperAndForeignSessionAreRejected() {
        String line = WireAuth.encodeFrame(session, WireAuth.PLATFORM_TO_ADAPTER, 1, env());
        assertThat(codeOf(() -> WireAuth.decodeFrame(session, WireAuth.PLATFORM_TO_ADAPTER, 2, line)))
                .as("replayed frame").isEqualTo(ErrorCode.REPLAY_REJECTED);
        assertThat(codeOf(() -> WireAuth.decodeFrame(session, WireAuth.ADAPTER_TO_PLATFORM, 1, line)))
                .as("reflected frame").isEqualTo(ErrorCode.REPLAY_REJECTED);
        String tampered = line.replace("mock-01", "mock-02");
        assertThat(codeOf(() -> WireAuth.decodeFrame(session, WireAuth.PLATFORM_TO_ADAPTER, 1, tampered)))
                .as("tampered frame").isEqualTo(ErrorCode.REPLAY_REJECTED);
        byte[] other = WireAuth.sessionKey(key, WireAuth.newNonce(), cn);
        assertThat(codeOf(() -> WireAuth.decodeFrame(other, WireAuth.PLATFORM_TO_ADAPTER, 1, line)))
                .as("frame from another connection").isEqualTo(ErrorCode.REPLAY_REJECTED);
        assertThat(codeOf(() -> WireAuth.decodeFrame(session, WireAuth.PLATFORM_TO_ADAPTER, 1, "{\"n\":1}")))
                .isEqualTo(ErrorCode.MALFORMED_FRAME);
    }

    /** 与 Node 实现共用的固定向量（sdk/protocol/fixtures/wire-v1-vector.json，由独立 HMAC 实现生成）。 */
    @Test
    void crossLanguageVector() {
        tools.jackson.databind.JsonNode v = new tools.jackson.databind.json.JsonMapper().readTree(
                io.github.dipinllx.minimacappbridge.protocol.RepoFiles.read("sdk/protocol/fixtures/wire-v1-vector.json"));
        byte[] k = java.util.Base64.getDecoder().decode(v.get("serviceKeyBase64").stringValue());
        String a = v.get("adapterNonce").stringValue();
        String c = v.get("clientNonce").stringValue();
        String id = v.get("instanceId").stringValue();
        long g = v.get("generation").longValue();
        String p = v.get("protocol").stringValue();
        assertThat(WireAuth.platformProof(k, a, c, id, g, p)).isEqualTo(v.get("platformProof").stringValue());
        assertThat(WireAuth.adapterProof(k, a, c, id, g, p)).isEqualTo(v.get("adapterProof").stringValue());
        byte[] sk = WireAuth.sessionKey(k, a, c);
        assertThat(java.util.HexFormat.of().formatHex(sk)).isEqualTo(v.get("sessionKeyHex").stringValue());
        assertThat(WireAuth.frameMac(sk, v.get("frameDirection").stringValue(), v.get("frameJson").stringValue()))
                .isEqualTo(v.get("frameMac").stringValue());
    }

    @Test
    void constantTimeEqualsHandlesNull() {
        assertThat(WireAuth.constantTimeEquals(null, "a")).isFalse();
        assertThat(WireAuth.constantTimeEquals("ab", "ab")).isTrue();
        assertThatThrownBy(() -> WireAuth.decodeFrame(session, "p2a", 1, "x".repeat(64) + " {}"))
                .isInstanceOf(ProtocolException.class);
    }
}
