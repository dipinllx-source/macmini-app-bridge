package io.github.dipinllx.minimacappbridge.instances;

import io.github.dipinllx.minimacappbridge.instances.AdapterDescriptor.OperationKind;
import io.github.dipinllx.minimacappbridge.instances.AdapterDescriptor.OperationSpec;
import io.github.dipinllx.minimacappbridge.protocol.CapabilityState;
import io.github.dipinllx.minimacappbridge.protocol.Envelope;
import io.github.dipinllx.minimacappbridge.protocol.ProtocolVersion;
import io.github.dipinllx.minimacappbridge.protocol.StandardCapability;
import io.github.dipinllx.minimacappbridge.protocol.StrategyFamily;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * 读取 {@code adapter.yaml}：SafeConstructor（不实例化任意类型），并做与
 * {@code sdk/protocol/adapter.schema.json} 一致的关键校验。完整 schema 校验由构建脚本用 JSON Schema 执行。
 */
public final class AdapterDescriptorLoader {

    private static final Set<String> TOP_LEVEL = Set.of("schemaVersion", "appId", "displayName", "version",
            "strategy", "protocol", "app", "permissions", "risk", "capabilities", "operations", "resources", "package");

    private AdapterDescriptorLoader() {
    }

    public static AdapterDescriptor load(Path file) throws IOException {
        return parse(Files.readString(file, StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    public static AdapterDescriptor parse(String yamlText) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(10);
        Object root = new Yaml(new SafeConstructor(options)).load(yamlText);
        if (!(root instanceof Map<?, ?> map)) {
            throw invalid("root must be a mapping");
        }
        Map<String, Object> m = (Map<String, Object>) map;
        for (String k : m.keySet()) {
            if (!TOP_LEVEL.contains(k)) {
                throw invalid("unknown top-level key: " + k);
            }
        }
        if (!Integer.valueOf(1).equals(m.get("schemaVersion"))) {
            throw invalid("schemaVersion must be 1");
        }
        String appId = str(m, "appId");
        if (!appId.matches("[a-z][a-z0-9-]{1,31}")) {
            throw invalid("invalid appId");
        }
        String version = m.containsKey("version") ? str(m, "version") : "0.0.0";
        if (!version.matches("[0-9A-Za-z.+-]{1,32}")) {
            throw invalid("invalid version");
        }
        StrategyFamily strategy = StrategyFamily.fromId(str(m, "strategy"))
                .orElseThrow(() -> invalid("unknown strategy"));
        Map<String, Object> protocol = mapOf(m, "protocol");
        ProtocolVersion min = ProtocolVersion.parse(str(protocol, "min"));
        ProtocolVersion max = ProtocolVersion.parse(str(protocol, "max"));
        if (min.compareTo(max) > 0) {
            throw invalid("protocol.min > protocol.max");
        }
        Map<String, Object> risk = mapOf(m, "risk");
        String level = str(risk, "level");
        if (!List.of("low", "medium", "high").contains(level)) {
            throw invalid("invalid risk.level");
        }

        Map<StandardCapability, CapabilityState> caps = new EnumMap<>(StandardCapability.class);
        for (Map.Entry<String, Object> e : mapOf(m, "capabilities").entrySet()) {
            StandardCapability c = StandardCapability.fromId(e.getKey())
                    .orElseThrow(() -> invalid("unknown capability"));
            try {
                caps.put(c, CapabilityState.valueOf(String.valueOf(e.getValue())));
            } catch (IllegalArgumentException ex) {
                throw invalid("invalid capability state");
            }
        }

        String namespace = appId.replace("-", "");
        Map<String, OperationSpec> ops = new LinkedHashMap<>();
        if (m.containsKey("operations")) {
            for (Map.Entry<String, Object> e : mapOf(m, "operations").entrySet()) {
                String name = e.getKey();
                if (!Envelope.OPERATION_PATTERN.matcher(name).matches() || name.length() > 64) {
                    throw invalid("invalid operation name");
                }
                if (!name.startsWith(namespace + ".")) {
                    throw invalid("operation outside namespace '" + namespace + "'");
                }
                if (!(e.getValue() instanceof Map<?, ?> specMap)) {
                    throw invalid("operation spec must be a mapping");
                }
                Map<String, Object> spec = (Map<String, Object>) specMap;
                for (String k : spec.keySet()) {
                    if (!Set.of("capability", "kind", "coalescible").contains(k)) {
                        throw invalid("unknown operation key");
                    }
                }
                StandardCapability cap = StandardCapability.fromId(str(spec, "capability"))
                        .orElseThrow(() -> invalid("unknown operation capability"));
                OperationKind kind = switch (str(spec, "kind")) {
                    case "request" -> OperationKind.REQUEST;
                    case "event" -> OperationKind.EVENT;
                    default -> throw invalid("operation kind must be request|event");
                };
                boolean coalescible = Boolean.TRUE.equals(spec.get("coalescible"));
                if (coalescible && kind != OperationKind.EVENT) {
                    throw invalid("only events may be coalescible");
                }
                ops.put(name, new OperationSpec(name, cap, kind, coalescible));
            }
        }
        String source = str(mapOf(m, "resources"), "source");
        return new AdapterDescriptor(appId, str(m, "displayName"), version, strategy, min, max, level, caps, ops,
                source);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (!(v instanceof Map<?, ?>)) {
            throw invalid(key + " must be a mapping");
        }
        return (Map<String, Object>) v;
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (!(v instanceof String s) || s.isBlank()) {
            throw invalid(key + " must be a non-empty string");
        }
        return s;
    }

    private static IllegalArgumentException invalid(String detail) {
        return new IllegalArgumentException("invalid adapter.yaml: " + detail);
    }
}
