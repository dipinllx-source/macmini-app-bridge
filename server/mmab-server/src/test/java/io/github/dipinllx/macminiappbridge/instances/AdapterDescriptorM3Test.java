package io.github.dipinllx.macminiappbridge.instances;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dipinllx.macminiappbridge.TestSupport;
import io.github.dipinllx.macminiappbridge.protocol.ErrorCode;
import io.github.dipinllx.macminiappbridge.protocol.StandardCapability;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** M3：web 注入、sync 操作、平台证据层级与操作者验证授权（ADR-0005）。 */
class AdapterDescriptorM3Test {

    private static final String BASE = """
            schemaVersion: 1
            appId: demo
            displayName: Demo
            version: 1.0.0
            strategy: electron-main-inject
            protocol: { min: "1.0", max: "1.0" }
            app: { bundleId: x, executableAllowlist: [x] }
            permissions: { launchArguments: false, temporaryInspector: false, persistentCdp: false, fileUploadDir: false }
            risk: { level: low, notes: [] }
            resources: { source: extract-from-app-bundle }
            capabilities: { ui.render: SUPPORTED, ui.compose: UNVERIFIED, app.git: DISABLED }
            operations:
              demo.call: { capability: ui.render, kind: request, sync: true }
              demo.go: { capability: ui.render, kind: request }
              demo.push: { capability: ui.render, kind: event }
              demo.write: { capability: ui.compose, kind: request }
            """;

    @Test
    void evidenceDeclarationKeepsSupportedCapabilitiesClosedUntilLiveEvidenceOrGrant() {
        AdapterDescriptor offline = AdapterDescriptorLoader.parse(BASE + "evidence: { ui.render: offline }\n");
        assertThat(offline.gateRequest("demo.go")).contains(ErrorCode.CAPABILITY_DISABLED);
        assertThat(offline.eventAllowed("demo.push")).isFalse();

        AdapterDescriptor granted = offline.withGrants(Set.of(StandardCapability.UI_RENDER));
        assertThat(granted.gateRequest("demo.go")).isEmpty();
        assertThat(granted.eventAllowed("demo.push")).isTrue();
        assertThat(granted.gateRequest("demo.write")).as("UNVERIFIED stays closed").contains(ErrorCode.CAPABILITY_DISABLED);

        AdapterDescriptor live = AdapterDescriptorLoader.parse(BASE + "evidence: { ui.render: isolated-live }\n");
        assertThat(live.gateRequest("demo.go")).isEmpty();
        assertThatThrownBy(() -> live.withGrants(Set.of(StandardCapability.UI_RENDER)))
                .as("nothing to grant").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> offline.withGrants(Set.of(StandardCapability.UI_COMPOSE)))
                .as("UNVERIFIED cannot be granted").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> offline.withGrants(Set.of(StandardCapability.APP_GIT)))
                .as("DISABLED cannot be granted").isInstanceOf(IllegalArgumentException.class);

        AdapterDescriptor legacy = AdapterDescriptorLoader.parse(BASE);
        assertThat(legacy.gateRequest("demo.go")).as("no evidence block = M1 semantics").isEmpty();
        assertThatThrownBy(() -> legacy.withGrants(Set.of(StandardCapability.UI_RENDER)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void syncFlagIsRequiredForTheSyncEndpoint() {
        AdapterDescriptor d = AdapterDescriptorLoader.parse(BASE);
        assertThat(d.gateSyncRequest("demo.call")).isEmpty();
        assertThat(d.gateSyncRequest("demo.go")).contains(ErrorCode.UNKNOWN_OPERATION);
        assertThat(d.gateSyncRequest("demo.write")).contains(ErrorCode.CAPABILITY_DISABLED);
        assertThatThrownBy(() -> AdapterDescriptorLoader.parse(BASE.replace(
                "demo.push: { capability: ui.render, kind: event }", "demo.push: { capability: ui.render, kind: event, sync: true }")))
                .hasMessageContaining("sync");
    }

    @Test
    void webInjectionEntriesAreStrictlyValidated() {
        AdapterDescriptor d = AdapterDescriptorLoader.parse(BASE
                + "web: { inject: [platform:codec.js, adapter:shim.js, resource:/mmab-preload.js] }\n");
        assertThat(d.web().inject()).hasSize(3);
        for (String bad : new String[] {"http://evil/x.js", "platform:../x.js", "adapter:a/b.js", "resource:/../x.js",
                "resource:x.js", "platform:x.css", "adapter:\"x.js", "resource:/a/./b.js"}) {
            assertThatThrownBy(() -> AdapterDescriptorLoader.parse(BASE + "web: { inject: [\"" + bad.replace("\"", "\\\"")
                    + "\"] }\n")).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> AdapterDescriptorLoader.parse(BASE + "web: { inject: [] }\n"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AdapterDescriptorLoader.parse(BASE + "web: { inject: [adapter:a.js], csp: x }\n"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AdapterDescriptorLoader.parse(BASE + "evidence: { ui.render: guessed }\n"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * M1 验收第 8 项写的是"构建时用 JSON Schema 校验 adapter.yaml"，但仓库里并没有 JSON Schema 校验器（M3 复核发现）。
     * 这里至少保证 schema 与 Java 加载器的键集合一致，避免两边各自漂移；完整 schema 校验仍是待办（ADR-0005）。
     */
    @Test
    void schemaKeySetsMatchTheLoader() throws Exception {
        JsonNode schema = new JsonMapper().readTree(Files.readString(
                TestSupport.repoRoot().resolve("sdk/protocol/adapter.schema.json")));
        Set<String> top = new HashSet<>(schema.get("properties").propertyNames());
        assertThat(top).containsExactlyInAnyOrder("schemaVersion", "appId", "displayName", "version", "strategy",
                "protocol", "app", "permissions", "risk", "capabilities", "operations", "resources", "package", "web",
                "evidence");
        Set<String> opKeys = new HashSet<>(schema.get("properties").get("operations").get("additionalProperties")
                .get("properties").propertyNames());
        assertThat(opKeys).containsExactlyInAnyOrder("capability", "kind", "coalescible", "sync");
        Set<String> caps = new HashSet<>(schema.get("properties").get("capabilities").get("properties").propertyNames());
        Set<String> evidence = new HashSet<>(schema.get("properties").get("evidence").get("properties").propertyNames());
        assertThat(evidence).isEqualTo(caps);
        for (StandardCapability c : StandardCapability.values()) {
            assertThat(caps).contains(c.id());
        }
    }
}
