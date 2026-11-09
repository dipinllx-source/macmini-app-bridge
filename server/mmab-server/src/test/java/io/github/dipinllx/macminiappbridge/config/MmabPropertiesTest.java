package io.github.dipinllx.macminiappbridge.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class MmabPropertiesTest {

    private static MmabProperties.Instance instance(String id, int port) {
        return new MmabProperties.Instance(id, "app", id, port, "electron-main-inject", false);
    }

    private static MmabProperties props(MmabProperties.Console console, List<MmabProperties.Instance> instances) {
        return new MmabProperties(null, console, instances, null, null, null, null, null, null);
    }

    @Test
    void rejectsPortCollisionWithConsole() {
        assertThatThrownBy(() -> props(new MmabProperties.Console(8443, "127.0.0.1"),
                List.of(instance("qwen-01", 8443))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsDuplicateInstanceId() {
        assertThatThrownBy(() -> props(null, List.of(instance("qwen-01", 8444), instance("qwen-01", 8446))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsWildcardBind() {
        assertThatThrownBy(() -> new MmabProperties.Console(8443, "0.0.0.0"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ephemeralPortsDoNotCollide() {
        MmabProperties p = props(new MmabProperties.Console(0, "127.0.0.1"),
                List.of(instance("mock-01", 0), instance("mock-02", 0)));
        assertThat(p.instances()).hasSize(2);
        assertThat(p.publicHosts()).containsExactly("127.0.0.1");
    }

    @Test
    void defaultsFollowDesign() {
        MmabProperties p = props(null, List.of());
        assertThat(p.timeouts().ticketTtl()).isEqualTo(Duration.ofSeconds(30));
        assertThat(p.timeouts().leaseTtl()).isEqualTo(Duration.ofSeconds(60));
        // reliable-personal-entry：会话最长 7 天，闲置期限默认等于最长期限
        assertThat(p.timeouts().sessionMaxAge()).isEqualTo(Duration.ofDays(7));
        assertThat(p.timeouts().sessionIdle()).isEqualTo(Duration.ofDays(7));
        assertThat(p.testing().allowTestAdapters()).isFalse();
        assertThat(p.web().tlsBundle()).isNull();
    }

    @Test
    void sessionLifetimeIsConfigurableAndIdleNeverExceedsIt() {
        var t = new MmabProperties.Timeouts(null, null, Duration.ofDays(30), null, null, null, null);
        assertThat(t.sessionMaxAge()).isEqualTo(Duration.ofDays(30));
        assertThat(t.sessionIdle()).as("idle defaults to the lifetime").isEqualTo(Duration.ofDays(30));
        var shorter = new MmabProperties.Timeouts(null, null, Duration.ofDays(7), Duration.ofHours(12), null, null, null);
        assertThat(shorter.sessionIdle()).isEqualTo(Duration.ofHours(12));
        var clamped = new MmabProperties.Timeouts(null, null, Duration.ofDays(1), Duration.ofDays(3), null, null, null);
        assertThat(clamped.sessionIdle()).isEqualTo(Duration.ofDays(1));
    }

    @Test
    void rejectsInvalidPublicHost() {
        assertThatThrownBy(() -> new MmabProperties.Web(List.of("evil.example:1"), null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
