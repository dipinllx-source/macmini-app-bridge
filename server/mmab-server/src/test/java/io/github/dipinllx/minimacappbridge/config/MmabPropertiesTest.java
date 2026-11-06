package io.github.dipinllx.minimacappbridge.config;

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
        assertThat(p.timeouts().sessionMaxAge()).isEqualTo(Duration.ofHours(8));
        assertThat(p.timeouts().sessionIdle()).isEqualTo(Duration.ofMinutes(30));
        assertThat(p.testing().allowTestAdapters()).isFalse();
        assertThat(p.web().tlsBundle()).isNull();
    }

    @Test
    void rejectsInvalidPublicHost() {
        assertThatThrownBy(() -> new MmabProperties.Web(List.of("evil.example:1"), null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
