package io.github.dipinllx.macminiappbridge.instances;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dipinllx.macminiappbridge.TestSupport;
import io.github.dipinllx.macminiappbridge.protocol.ErrorCode;
import io.github.dipinllx.macminiappbridge.protocol.StandardCapability;
import io.github.dipinllx.macminiappbridge.protocol.StrategyFamily;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** M3：千问参考适配器的描述文件——操作白名单、同步操作、注入顺序与证据闸门。 */
class QwenworkDescriptorTest {

    @Test
    void qwenworkDescriptorDeclaresTheS1SurfaceAndStaysClosedOnOfflineEvidence() throws Exception {
        AdapterDescriptor d = AdapterDescriptorLoader.load(TestSupport.repoRoot().resolve("adapters/qwenwork/adapter.yaml"));
        assertThat(d.appId()).isEqualTo("qwenwork");
        assertThat(d.strategy()).isEqualTo(StrategyFamily.S1_ELECTRON_MAIN_INJECT);
        assertThat(d.supportsCurrentProtocol()).isTrue();

        // preload emulation needs the transport before the wrapped original preload, in this order
        assertThat(d.web().inject()).containsExactly("platform:codec.js", "platform:transport.js",
                "platform:electron-compat.js", "adapter:qwen-electron.js", "resource:/mmab-preload.js");

        // only offline evidence so far: SUPPORTED render capability is not opened on the platform path
        assertThat(d.gateRequest("qwenwork.invoke")).contains(ErrorCode.CAPABILITY_DISABLED);
        assertThat(d.eventAllowed("qwenwork.ipc")).isFalse();
        assertThat(d.gateRequest("qwenwork.unknown")).contains(ErrorCode.UNKNOWN_OPERATION);

        // an explicit operator grant on the instance opens exactly the declared surface
        AdapterDescriptor granted = d.withGrants(Set.of(StandardCapability.UI_RENDER));
        assertThat(granted.gateRequest("qwenwork.invoke")).isEmpty();
        assertThat(granted.gateRequest("qwenwork.send")).isEmpty();
        assertThat(granted.gateSyncRequest("qwenwork.bootstrap")).isEmpty();
        assertThat(granted.gateSyncRequest("qwenwork.sendSync")).isEmpty();
        assertThat(granted.gateSyncRequest("qwenwork.invoke")).as("invoke is not a sync operation").isPresent();
        assertThat(granted.eventAllowed("qwenwork.ipc")).isTrue();
    }
}
