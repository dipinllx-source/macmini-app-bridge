package io.github.dipinllx.minimacappbridge.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class EnvelopeTest {

    private static Envelope envelope(MessageKind kind, String correlationId, String payload) {
        return new Envelope(AdapterProtocol.CURRENT, "codex/26.1103.31037", "codex-01", 3, "conn-1",
                "lease-1", 7, null, 0, correlationId, kind, Instant.parse("2026-11-07T00:00:30Z"), "c1", payload);
    }

    @Test
    void acceptsMinimalRequest() {
        Envelope e = envelope(MessageKind.REQUEST, "r-1", "{}");
        assertThat(e.matchesFence(3, 7)).isTrue();
        assertThat(e.matchesFence(4, 7)).isFalse();
        assertThat(e.isExpired(Instant.parse("2026-11-07T00:00:31Z"))).isTrue();
        assertThat(e.exceedsFrameBudget()).isFalse();
    }

    @Test
    void responseRequiresCorrelationId() {
        assertThatThrownBy(() -> envelope(MessageKind.RESPONSE, null, "{}"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBlankInstance() {
        assertThatThrownBy(() -> new Envelope(AdapterProtocol.CURRENT, "p", " ", 0, "c", "l", 0, null, 0, null,
                MessageKind.EVENT, Instant.EPOCH, "c1", ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void detectsOversizedFrame() {
        String big = "x".repeat(AdapterProtocol.MAX_FRAME_BYTES + 1);
        assertThat(envelope(MessageKind.EVENT, null, big).exceedsFrameBudget()).isTrue();
    }
}
