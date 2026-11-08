package io.github.dipinllx.macminiappbridge.protocol.ports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dipinllx.macminiappbridge.protocol.ErrorCode;
import io.github.dipinllx.macminiappbridge.protocol.ProtocolException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class PortTableTest {

    private Instant now = Instant.parse("2026-11-07T00:00:00Z");
    private final Clock clock = new Clock() {
        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    };

    private static ErrorCode codeOf(Runnable r) {
        try {
            r.run();
        } catch (ProtocolException e) {
            return e.code();
        }
        return null;
    }

    @Test
    void transferMovesOwnershipAndRejectsRepeatTransferByOldOwner() {
        PortTable t = new PortTable(clock);
        t.open("p1", "web", null);
        t.transfer("p1", "web", "native");
        assertThat(t.get("p1").owner()).isEqualTo("native");
        assertThat(codeOf(() -> t.transfer("p1", "web", "native"))).isEqualTo(ErrorCode.PORT_STATE);
        assertThat(codeOf(() -> t.data("p1", "web"))).isEqualTo(ErrorCode.PORT_STATE);
        t.data("p1", "native");
    }

    @Test
    void framesAfterCloseAreRejectedAndCloseIsIdempotent() {
        PortTable t = new PortTable(clock);
        t.open("p1", "web", null);
        assertThat(t.close("p1", "web")).isTrue();
        assertThat(t.close("p1", "web")).isFalse();
        assertThat(codeOf(() -> t.data("p1", "web"))).isEqualTo(ErrorCode.PORT_STATE);
        assertThat(codeOf(() -> t.open("p1", "web", null))).as("ids are never reused").isEqualTo(ErrorCode.PORT_STATE);
        assertThat(codeOf(() -> t.data("nope", "web"))).isEqualTo(ErrorCode.PORT_STATE);
        assertThat(t.openCount()).isZero();
    }

    @Test
    void onlyOwnerMayClose() {
        PortTable t = new PortTable(clock);
        t.open("p1", "web", null);
        assertThat(codeOf(() -> t.close("p1", "native"))).isEqualTo(ErrorCode.PORT_STATE);
    }

    @Test
    void lostPeerClosesItsPortsOnly() {
        PortTable t = new PortTable(clock);
        t.open("a", "web", null);
        t.open("b", "native", null);
        assertThat(t.peerLost("native")).containsExactly("b");
        t.data("a", "web");
        assertThat(codeOf(() -> t.data("b", "native"))).isEqualTo(ErrorCode.PORT_STATE);
    }

    @Test
    void enforcesOpenLimitAndNestingDepth() {
        PortTable t = new PortTable(2, 2, Duration.ofMinutes(5), clock);
        t.open("root", "web", null);
        t.open("child", "web", "root");
        assertThat(codeOf(() -> t.open("x", "web", null))).isEqualTo(ErrorCode.QUEUE_LIMIT);
        t.close("root", "web");
        assertThat(codeOf(() -> t.open("grandchild", "web", "child"))).isEqualTo(ErrorCode.QUEUE_LIMIT);
        assertThat(codeOf(() -> t.open("orphan", "web", "root"))).as("parent closed").isEqualTo(ErrorCode.PORT_STATE);
    }

    @Test
    void reapsIdlePorts() {
        PortTable t = new PortTable(clock);
        t.open("idle", "web", null);
        t.open("busy", "web", null);
        now = now.plus(Duration.ofMinutes(4));
        t.data("busy", "web");
        now = now.plus(Duration.ofMinutes(2));
        assertThat(t.reapIdle()).containsExactly("idle");
        assertThat(t.closeAll()).containsExactly("busy");
    }
}
