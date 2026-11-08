package io.github.dipinllx.macminiappbridge.instances;

import io.github.dipinllx.macminiappbridge.instances.AdapterDescriptor.OperationKind;
import io.github.dipinllx.macminiappbridge.instances.AdapterDescriptor.OperationSpec;
import io.github.dipinllx.macminiappbridge.protocol.CapabilityState;
import io.github.dipinllx.macminiappbridge.protocol.Envelope;
import io.github.dipinllx.macminiappbridge.protocol.ProtocolVersion;
import io.github.dipinllx.macminiappbridge.protocol.StandardCapability;
import io.github.dipinllx.macminiappbridge.protocol.StrategyFamily;
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
            "strategy", "protocol", "app", "permissions", "risk", "capabilities", "operations", "resources", "package",
            "web", "evidence");

    /** 注入项：platform:/adapter: 脚本名，或 resource:/ 资源根内的 .js 路径。 */
    private static final java.util.regex.Pattern INJECT = java.util.regex.Pattern.compile(
            "^((platform|adapter):[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]+)*\\.m?js|resource:(/[A-Za-z0-9_-][A-Za-z0-9._-]*)+\\.js)$");

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
                    if (!Set.of("capability", "kind", "coalescible", "sync").contains(k)) {
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
                boolean sync = Boolean.TRUE.equals(spec.get("sync"));
                if (sync && kind != OperationKind.REQUEST) {
                    throw invalid("only requests may be sync");
                }
                ops.put(name, new OperationSpec(name, cap, kind, coalescible, sync));
            }
        }
        String source = str(mapOf(m, "resources"), "source");
        AdapterDescriptor.Web web = null;
        if (m.containsKey("web")) {
            Map<String, Object> w = mapOf(m, "web");
            for (String k : w.keySet()) {
                if (!"inject".equals(k)) {
                    throw invalid("unknown web key");
                }
            }
            if (!(w.get("inject") instanceof List<?> list) || list.isEmpty() || list.size() > 16) {
                throw invalid("web.inject must be a non-empty list (max 16)");
            }
            List<String> inject = new java.util.ArrayList<>();
            for (Object o : list) {
                if (!(o instanceof String e) || !INJECT.matcher(e).matches() || e.contains("..")) {
                    throw invalid("invalid web.inject entry");
                }
                inject.add(e);
            }
            web = new AdapterDescriptor.Web(inject);
        }
        Map<StandardCapability, AdapterDescriptor.Evidence> evidence = new EnumMap<>(StandardCapability.class);
        if (m.containsKey("evidence")) {
            for (Map.Entry<String, Object> e : mapOf(m, "evidence").entrySet()) {
                StandardCapability c = StandardCapability.fromId(e.getKey())
                        .orElseThrow(() -> invalid("unknown evidence capability"));
                evidence.put(c, AdapterDescriptor.Evidence.fromId(String.valueOf(e.getValue()))
                        .orElseThrow(() -> invalid("evidence must be offline|isolated-live|live")));
            }
            if (evidence.isEmpty()) {
                throw invalid("evidence must not be empty when present");
            }
        }
        return new AdapterDescriptor(appId, str(m, "displayName"), version, strategy, min, max, level, caps, ops,
                source, web, evidence, Set.of());
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
