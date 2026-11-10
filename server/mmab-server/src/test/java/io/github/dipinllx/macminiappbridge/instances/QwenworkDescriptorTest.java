package io.github.dipinllx.macminiappbridge.instances;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dipinllx.macminiappbridge.TestSupport;
import io.github.dipinllx.macminiappbridge.protocol.ErrorCode;
import io.github.dipinllx.macminiappbridge.protocol.StandardCapability;
import io.github.dipinllx.macminiappbridge.protocol.StrategyFamily;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** M3：千问参考适配器的描述文件——操作白名单、同步操作、注入顺序与证据闸门（实机证据后自动开放）。 */
class QwenworkDescriptorTest {

    @Test
    void qwenworkDescriptorDeclaresTheS1SurfaceAndOpensOnLiveEvidence() throws Exception {
        AdapterDescriptor d = AdapterDescriptorLoader.load(TestSupport.repoRoot().resolve("adapters/qwenwork/adapter.yaml"));
        assertThat(d.appId()).isEqualTo("qwenwork");
        assertThat(d.strategy()).isEqualTo(StrategyFamily.S1_ELECTRON_MAIN_INJECT);
        assertThat(d.supportsCurrentProtocol()).isTrue();

        // preload emulation needs the transport before the wrapped original preload, in this order
        assertThat(d.web().inject()).containsExactly("platform:codec.js", "platform:transport.js",
                "platform:electron-compat.js", "adapter:qwen-picker.js", "adapter:qwen-electron.js",
                "resource:/mmab-preload.js");

        // live evidence on the platform path (2026-11-09): the declared surface is open without an operator grant
        assertThat(d.gateRequest("qwenwork.invoke")).isEmpty();
        assertThat(d.gateRequest("qwenwork.send")).isEmpty();
        assertThat(d.gateSyncRequest("qwenwork.bootstrap")).isEmpty();
        assertThat(d.gateSyncRequest("qwenwork.sendSync")).isEmpty();
        assertThat(d.gateSyncRequest("qwenwork.invoke")).as("invoke is not a sync operation").isPresent();
        assertThat(d.eventAllowed("qwenwork.ipc")).isTrue();
        assertThat(d.gateRequest("qwenwork.unknown")).contains(ErrorCode.UNKNOWN_OPERATION);
        // file.pick（网页内目录选择器的数据源）尚无 live 证据：默认关闭，操作者授权验证窗口后开放
        assertThat(d.gateRequest("qwenwork.fs.list")).isPresent();
        assertThat(d.gateRequest("qwenwork.fs.describe")).isPresent();
        AdapterDescriptor granted = d.withGrants(Set.of(StandardCapability.FILE_PICK));
        assertThat(granted.gateRequest("qwenwork.fs.list")).isEmpty();
        assertThat(granted.gateRequest("qwenwork.fs.describe")).isEmpty();
        assertThatThrownBy(() -> d.withGrants(Set.of(StandardCapability.UI_RENDER)))
                .as("nothing left to grant once live evidence exists").isInstanceOf(IllegalArgumentException.class);
    }
}
