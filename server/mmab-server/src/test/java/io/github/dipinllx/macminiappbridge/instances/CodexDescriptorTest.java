package io.github.dipinllx.macminiappbridge.instances;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dipinllx.macminiappbridge.TestSupport;
import io.github.dipinllx.macminiappbridge.protocol.ErrorCode;
import io.github.dipinllx.macminiappbridge.protocol.StrategyFamily;
import org.junit.jupiter.api.Test;

/** M3：Codex 参考适配器的描述文件——S2 表面、注入顺序、Git 能力单独闸门。 */
class CodexDescriptorTest {

    @Test
    void codexDescriptorDeclaresTheS2SurfaceWithGitGatedSeparately() throws Exception {
        AdapterDescriptor d = AdapterDescriptorLoader.load(TestSupport.repoRoot().resolve("adapters/codex/adapter.yaml"));
        assertThat(d.appId()).isEqualTo("codex");
        assertThat(d.strategy()).isEqualTo(StrategyFamily.S2_ELECTRON_RENDERER_CDP);
        assertThat(d.web().inject()).containsExactly("platform:codec.js", "platform:transport.js", "platform:ports.js",
                "adapter:codex-bridge.js");

        // isolated-live evidence on the platform path: ui.render opens without an operator grant
        AdapterDescriptor granted = d;
        assertThat(granted.gateSyncRequest("codex.bootstrap")).isEmpty();
        assertThat(granted.gateRequest("codex.connectAppHost")).isEmpty();
        assertThat(granted.eventAllowed("codex.hostMessage")).isTrue();
        assertThat(granted.coalescible("codex.theme")).isTrue();
        // Git stays closed (UNVERIFIED) even when ui.render is open
        assertThat(granted.gateRequest("codex.connectGit")).contains(ErrorCode.CAPABILITY_DISABLED);
        assertThat(granted.eventAllowed("codex.gitPort")).isFalse();
        assertThat(granted.gateSyncRequest("codex.connectAppHost")).as("not a sync operation").isPresent();
    }
}
