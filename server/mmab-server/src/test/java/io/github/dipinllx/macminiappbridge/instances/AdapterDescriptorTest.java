package io.github.dipinllx.macminiappbridge.instances;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dipinllx.macminiappbridge.TestSupport;
import io.github.dipinllx.macminiappbridge.protocol.CapabilityState;
import io.github.dipinllx.macminiappbridge.protocol.ErrorCode;
import io.github.dipinllx.macminiappbridge.protocol.StandardCapability;
import io.github.dipinllx.macminiappbridge.protocol.StrategyFamily;
import org.junit.jupiter.api.Test;

/** adapter.yaml：能力四态、操作白名单、命名空间、未知操作明确拒绝（设计 §14.3、§14.4）。 */
class AdapterDescriptorTest {

    private static AdapterDescriptor load(String appId) throws Exception {
        return AdapterDescriptorLoader.load(TestSupport.repoRoot().resolve("adapters/" + appId + "/adapter.yaml"));
    }

    @Test
    void referenceAdaptersLoadWithUnchangedCapabilityFacts() throws Exception {
        AdapterDescriptor qwen = load("qwenwork");
        assertThat(qwen.strategy()).isEqualTo(StrategyFamily.S1_ELECTRON_MAIN_INJECT);
        assertThat(qwen.state(StandardCapability.UI_HISTORY)).isEqualTo(CapabilityState.SUPPORTED);
        assertThat(qwen.state(StandardCapability.UI_COMPOSE)).isEqualTo(CapabilityState.UNVERIFIED);
        AdapterDescriptor codex = load("codex");
        assertThat(codex.strategy()).isEqualTo(StrategyFamily.S2_ELECTRON_RENDERER_CDP);
        assertThat(codex.state(StandardCapability.UI_RENDER)).isEqualTo(CapabilityState.SUPPORTED);
        assertThat(codex.state(StandardCapability.FILE_UPLOAD)).isEqualTo(CapabilityState.UNSUPPORTED);
        // 参考适配器尚未声明任何操作：网关对它们一律 UNKNOWN_OPERATION
        assertThat(qwen.gateRequest("qwenwork.anything")).contains(ErrorCode.UNKNOWN_OPERATION);
        assertThat(codex.gateRequest("codex.anything")).contains(ErrorCode.UNKNOWN_OPERATION);
    }

    @Test
    void mockGateFollowsCapabilityStates() throws Exception {
        AdapterDescriptor mock = load("mock");
        assertThat(mock.strategy()).isEqualTo(StrategyFamily.TEST_MOCK);
        assertThat(mock.profileId()).isEqualTo("mock/0.1.0");
        assertThat(mock.gateRequest("mock.echo")).isEmpty();
        assertThat(mock.gateRequest("mock.compose")).contains(ErrorCode.CAPABILITY_DISABLED);
        assertThat(mock.gateRequest("mock.nope")).contains(ErrorCode.UNKNOWN_OPERATION);
        assertThat(mock.gateRequest("mock.chunk")).as("events cannot be requested").contains(ErrorCode.UNKNOWN_OPERATION);
        assertThat(mock.gateRequest("mmab.fence")).as("platform namespace").contains(ErrorCode.UNKNOWN_OPERATION);
        assertThat(mock.gateRequest("codex.echo")).as("other namespace").contains(ErrorCode.UNKNOWN_OPERATION);
        assertThat(mock.gateRequest("Mock.Echo")).contains(ErrorCode.UNKNOWN_OPERATION);
        assertThat(mock.gateRequest(null)).contains(ErrorCode.UNKNOWN_OPERATION);
        assertThat(mock.eventAllowed("mock.tick")).isTrue();
        assertThat(mock.eventAllowed("mock.echo")).isFalse();
        assertThat(mock.coalescible("mock.tick")).isTrue();
        assertThat(mock.coalescible("mock.chunk")).isFalse();
    }

    @Test
    void everyCapabilityStateOtherThanSupportedIsClosed() {
        for (CapabilityState s : CapabilityState.values()) {
            String yaml = base() + "capabilities: { ui.render: " + s + " }\n"
                    + "operations:\n  demo.go: { capability: ui.render, kind: request }\n";
            AdapterDescriptor d = AdapterDescriptorLoader.parse(yaml);
            if (s == CapabilityState.SUPPORTED) {
                assertThat(d.gateRequest("demo.go")).isEmpty();
            } else {
                assertThat(d.gateRequest("demo.go")).as(s.name()).contains(ErrorCode.CAPABILITY_DISABLED);
            }
        }
        AdapterDescriptor missing = AdapterDescriptorLoader.parse(base() + "capabilities: {}\n"
                + "operations:\n  demo.go: { capability: ui.render, kind: request }\n");
        assertThat(missing.gateRequest("demo.go")).as("undeclared capability = UNSUPPORTED")
                .contains(ErrorCode.CAPABILITY_DISABLED);
    }

    @Test
    void loaderRejectsInvalidDescriptors() {
        String caps = "capabilities: { ui.render: SUPPORTED }\n";
        assertThatThrownBy(() -> AdapterDescriptorLoader.parse(base() + caps
                + "operations:\n  other.go: { capability: ui.render, kind: request }\n"))
                .hasMessageContaining("namespace");
        assertThatThrownBy(() -> AdapterDescriptorLoader.parse(base() + caps
                + "operations:\n  demo.go: { capability: ui.render, kind: request, coalescible: true }\n"))
                .hasMessageContaining("coalescible");
        assertThatThrownBy(() -> AdapterDescriptorLoader.parse(base() + "capabilities: { ui.render: MAYBE }\n"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AdapterDescriptorLoader.parse(base() + "capabilities: { ui.teleport: SUPPORTED }\n"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AdapterDescriptorLoader.parse(base() + caps + "surprise: 1\n"))
                .hasMessageContaining("unknown top-level key");
        assertThatThrownBy(() -> AdapterDescriptorLoader.parse(base() + caps + "appId: again\n"))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> AdapterDescriptorLoader.parse(base().replace("test-mock", "teleport") + caps))
                .hasMessageContaining("strategy");
    }

    private static String base() {
        return """
                schemaVersion: 1
                appId: demo
                displayName: Demo
                version: 1.0.0
                strategy: test-mock
                protocol: { min: "1.0", max: "1.0" }
                app: { bundleId: x, executableAllowlist: [x] }
                permissions: { launchArguments: false, temporaryInspector: false, persistentCdp: false, fileUploadDir: false }
                risk: { level: low, notes: [] }
                resources: { source: adapter-bundled }
                """;
    }
}
