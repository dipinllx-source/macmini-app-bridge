package io.github.dipinllx.macminiappbridge.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ProtocolVersionTest {

    @Test
    void parsesAndComparesMajor() {
        ProtocolVersion v = ProtocolVersion.parse("1.3");
        assertThat(v).isEqualTo(new ProtocolVersion(1, 3));
        assertThat(v.isCompatibleWith(AdapterProtocol.CURRENT)).isTrue();
        assertThat(v.isCompatibleWith(new ProtocolVersion(2, 0))).isFalse();
        assertThat(v.toString()).isEqualTo("1.3");
    }

    @Test
    void rejectsPreReleaseSuffix() {
        assertThatThrownBy(() -> ProtocolVersion.parse("1.0-rc1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProtocolVersion.parse("1")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void onlySupportedIsEnabledByDefault() {
        for (CapabilityState s : CapabilityState.values()) {
            assertThat(s.enabledByDefault()).isEqualTo(s == CapabilityState.SUPPORTED);
        }
        assertThat(StandardCapability.fromId("ui.render")).contains(StandardCapability.UI_RENDER);
        assertThat(StandardCapability.fromId("ui.unknown")).isEmpty();
    }
}
