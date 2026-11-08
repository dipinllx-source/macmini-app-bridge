package io.github.dipinllx.macminiappbridge.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class EnvelopeTest {

    private static Envelope.Builder base(MessageKind kind) {
        return Envelope.builder().profileId("codex/26.1103.31037").instanceId("codex-01").processGeneration(3)
                .connectionId("conn-1").leaseId("lease-1").fencingToken(7).messageKind(kind)
                .deadline(Instant.parse("2026-11-07T00:00:30Z")).payload("{}");
    }

    @Test
    void acceptsMinimalRequest() {
        Envelope e = base(MessageKind.REQUEST).correlationId("r-1").operation("mock.echo").build();
        assertThat(e.matchesFence(3, 7)).isTrue();
        assertThat(e.matchesFence(4, 7)).isFalse();
        assertThat(e.matchesFence(3, "lease-1", 7)).isTrue();
        assertThat(e.matchesFence(3, "lease-2", 7)).isFalse();
        assertThat(e.isExpired(Instant.parse("2026-11-07T00:00:31Z"))).isTrue();
        assertThat(e.exceedsFrameBudget()).isFalse();
        assertThat(e.codecVersion()).isEqualTo(AdapterProtocol.CODEC_VERSION);
    }

    @Test
    void responseAndRequestRequireCorrelationId() {
        assertThatThrownBy(() -> base(MessageKind.RESPONSE).build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> base(MessageKind.REQUEST).operation("mock.echo").build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requestAndControlRequireValidOperation() {
        assertThatThrownBy(() -> base(MessageKind.REQUEST).correlationId("r").build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> base(MessageKind.CONTROL).build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> base(MessageKind.REQUEST).correlationId("r").operation("Mock.Echo").build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> base(MessageKind.REQUEST).correlationId("r").operation("echo").build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void errorOnlyOnResponseControlPortError() {
        assertThatThrownBy(() -> base(MessageKind.EVENT).error(EnvelopeError.of(ErrorCode.QUEUE_LIMIT)).build())
                .isInstanceOf(IllegalArgumentException.class);
        Envelope req = base(MessageKind.REQUEST).correlationId("r-9").operation("mock.echo").build();
        Envelope err = req.errorResponse(ErrorCode.UNKNOWN_OPERATION, 11);
        assertThat(err.messageKind()).isEqualTo(MessageKind.RESPONSE);
        assertThat(err.correlationId()).isEqualTo("r-9");
        assertThat(err.error().code()).isEqualTo(ErrorCode.UNKNOWN_OPERATION);
        assertThat(err.error().message()).isEqualTo(ErrorCode.UNKNOWN_OPERATION.summary());
    }

    @Test
    void rejectsBlankOrInvalidIds() {
        assertThatThrownBy(() -> base(MessageKind.EVENT).instanceId(" ").build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> base(MessageKind.EVENT).instanceId("mock 01").build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> base(MessageKind.EVENT).processGeneration(-1).build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void detectsOversizedFrame() {
        String big = "x".repeat(AdapterProtocol.MAX_FRAME_BYTES + 1);
        assertThat(base(MessageKind.EVENT).payload(big).build().exceedsFrameBudget()).isTrue();
    }
}
