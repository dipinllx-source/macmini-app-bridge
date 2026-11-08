package io.github.dipinllx.macminiappbridge.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** M3：多地址精确绑定与 public-hosts 同步（设计 §5.1，ADR-0004 §3 的 M3 缺口）。 */
class MmabPropertiesM3Test {

    private static MmabProperties props(MmabProperties.Console console, List<String> publicHosts) {
        return new MmabProperties(null, console, List.of(),
                new MmabProperties.Web(publicHosts, null, null, null), null, null, null, null, null);
    }

    private static MmabProperties.Console console(String... addresses) {
        return new MmabProperties.Console(8443, null, List.of(addresses));
    }

    @Test
    void lanAndLoopbackBothBoundAndPublicHostsDefaultToThem() {
        MmabProperties p = props(console("10.1.2.3", "127.0.0.1"), List.of());
        assertThat(p.console().bindAddresses()).containsExactly("10.1.2.3", "127.0.0.1");
        assertThat(p.console().bindAddress()).as("primary").isEqualTo("10.1.2.3");
        assertThat(p.publicHosts()).containsExactly("10.1.2.3", "127.0.0.1");
        assertThat(p.console().allLoopback()).isFalse();
        assertThat(props(console("127.0.0.1", "::1"), List.of()).publicHosts()).containsExactly("127.0.0.1", "[::1]");
        assertThat(console("127.0.0.1", "::1").allLoopback()).isTrue();
    }

    @Test
    void singleAddressM1ConfigurationStillWorks() {
        MmabProperties p = props(new MmabProperties.Console(8443, "10.1.2.3"), List.of("10.1.2.3"));
        assertThat(p.console().bindAddresses()).containsExactly("10.1.2.3");
        assertThat(p.publicHosts()).containsExactly("10.1.2.3");
    }

    @Test
    void wildcardsHostnamesAndDuplicatesAreRejected() {
        for (String bad : List.of("0.0.0.0", "::", "*", "0:0:0:0:0:0:0:0")) {
            assertThatThrownBy(() -> console("127.0.0.1", bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> console("localhost")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> console("lan.example")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> console("224.0.0.1")).as("multicast").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> console("127.0.0.1", "127.0.0.1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> console("::1", "0:0:0:0:0:0:0:1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> console("10.1.2.3/24")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void publicHostsMustStayInSyncWithBindAddresses() {
        assertThatThrownBy(() -> props(console("10.1.2.3", "127.0.0.1"), List.of("10.1.2.3")))
                .as("loopback bound but not listed").hasMessageContaining("every bind address");
        assertThatThrownBy(() -> props(console("127.0.0.1"), List.of("127.0.0.1", "10.9.9.9")))
                .as("listed address not bound").hasMessageContaining("not a bind address");
        assertThatThrownBy(() -> props(console("127.0.0.1"), List.of("127.0.0.1", "[::1]")))
                .hasMessageContaining("not a bind address");
        MmabProperties withName = props(console("10.1.2.3", "127.0.0.1"),
                List.of("10.1.2.3", "127.0.0.1", "bridge.lan"));
        assertThat(withName.publicHosts()).contains("bridge.lan");
        assertThat(props(console("::1"), List.of("[0:0:0:0:0:0:0:1]")).publicHosts()).hasSize(1);
    }

    @Test
    void dataDirDefaultsOutsideTheRepository() {
        MmabProperties p = props(null, List.of());
        assertThat(p.dataDir()).endsWith("/Library/Application Support/MacminiAppBridge");
    }
}
