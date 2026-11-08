package io.github.dipinllx.macminiappbridge.instances;

import io.github.dipinllx.macminiappbridge.protocol.AdapterProtocol;
import io.github.dipinllx.macminiappbridge.protocol.CapabilityState;
import io.github.dipinllx.macminiappbridge.protocol.Envelope;
import io.github.dipinllx.macminiappbridge.protocol.ErrorCode;
import io.github.dipinllx.macminiappbridge.protocol.ProtocolVersion;
import io.github.dipinllx.macminiappbridge.protocol.StandardCapability;
import io.github.dipinllx.macminiappbridge.protocol.StrategyFamily;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 解析后的 {@code adapter.yaml}（设计 §14.3、§14.4）。网关据此做操作白名单与能力闸门：
 * 未声明的操作 → {@link ErrorCode#UNKNOWN_OPERATION}；能力不是 SUPPORTED → {@link ErrorCode#CAPABILITY_DISABLED}。
 *
 * @param appId        应用 ID，同时是操作命名空间（去掉连字符）
 * @param displayName  展示名
 * @param version      适配器包版本
 * @param strategy     策略族
 * @param protocolMin  支持的最低协议版本
 * @param protocolMax  支持的最高协议版本
 * @param riskLevel    风险等级
 * @param capabilities 能力四态
 * @param operations   操作白名单
 * @param resourceSource 资源来源
 * @param web          原版页面注入设置（M3）；为空时沿用 M1 的模块 shim
 * @param evidence     平台路径上的证据层级（M3，见 ADR-0005）；为空表示未声明（M1 语义：SUPPORTED 即开启）
 * @param grants       操作者为验证窗口显式开启的能力（实例配置 {@code verify-capabilities}），只能作用于
 *                     状态为 SUPPORTED 但平台证据不足的能力
 */
public record AdapterDescriptor(String appId, String displayName, String version, StrategyFamily strategy,
        ProtocolVersion protocolMin, ProtocolVersion protocolMax, String riskLevel,
        Map<StandardCapability, CapabilityState> capabilities, Map<String, OperationSpec> operations,
        String resourceSource, Web web, Map<StandardCapability, Evidence> evidence, Set<StandardCapability> grants) {

    /** 平台路径上的证据层级：离线自动化 &lt; 独立实例 live &lt; 正式实例 live。 */
    public enum Evidence {
        OFFLINE("offline"), ISOLATED_LIVE("isolated-live"), LIVE("live");

        private final String id;

        Evidence(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        public static Optional<Evidence> fromId(String id) {
            for (Evidence e : values()) {
                if (e.id.equals(id)) {
                    return Optional.of(e);
                }
            }
            return Optional.empty();
        }
    }

    /**
     * 原版首页注入（设计 §2.1 / §14.2）：按顺序插在第一个 {@code <head>} 之后的经典脚本。
     * {@code platform:x.js} → {@code /__mmab/shim/x.js}（sdk/web），{@code adapter:x.js} →
     * {@code /__mmab/app-shim/x.js}（适配器包 shim/ 目录），{@code resource:/p.js} → 实例资源根内的路径。
     *
     * @param inject 注入列表
     */
    public record Web(List<String> inject) {
        public Web {
            inject = inject == null ? List.of() : List.copyOf(inject);
        }

        /** 注入项 → 浏览器 URL。 */
        public static String url(String entry) {
            if (entry.startsWith("platform:")) {
                return "/__mmab/shim/" + entry.substring("platform:".length());
            }
            if (entry.startsWith("adapter:")) {
                return "/__mmab/app-shim/" + entry.substring("adapter:".length());
            }
            return entry.substring("resource:".length());
        }
    }

    /** M1 兼容构造器（无 web / evidence / grants）。 */
    public AdapterDescriptor(String appId, String displayName, String version, StrategyFamily strategy,
            ProtocolVersion protocolMin, ProtocolVersion protocolMax, String riskLevel,
            Map<StandardCapability, CapabilityState> capabilities, Map<String, OperationSpec> operations,
            String resourceSource) {
        this(appId, displayName, version, strategy, protocolMin, protocolMax, riskLevel, capabilities, operations,
                resourceSource, null, Map.of(), Set.of());
    }

    /** 平台保留的命名空间（控制帧），浏览器与适配器业务操作都不能使用。 */
    public static final String PLATFORM_NAMESPACE = "mmab";

    public enum OperationKind { REQUEST, EVENT }

    /**
     * @param name        操作名
     * @param capability  所属标准能力
     * @param kind        request（浏览器发起）/ event（适配器推送）
     * @param coalescible 仅 event：是否为可合并的状态通知（队列满时只保留最新值）
     * @param sync        仅 request：允许经同步端点 {@code POST /__mmab/bridge/call} 调用（启动引导 / sendSync 类）
     */
    public record OperationSpec(String name, StandardCapability capability, OperationKind kind, boolean coalescible,
            boolean sync) {

        /** M1 兼容构造器。 */
        public OperationSpec(String name, StandardCapability capability, OperationKind kind, boolean coalescible) {
            this(name, capability, kind, coalescible, false);
        }
    }

    public AdapterDescriptor {
        capabilities = Map.copyOf(capabilities);
        operations = Map.copyOf(operations);
        evidence = evidence == null ? Map.of() : Map.copyOf(evidence);
        grants = grants == null || grants.isEmpty() ? Set.of() : Set.copyOf(grants);
    }

    /**
     * 返回带操作者验证授权的副本。只允许授予"状态 SUPPORTED 但平台证据不足"的能力；
     * UNVERIFIED / DISABLED / UNSUPPORTED 不能靠配置打开。
     */
    public AdapterDescriptor withGrants(Set<StandardCapability> granted) {
        if (granted == null || granted.isEmpty()) {
            return this;
        }
        Set<StandardCapability> g = EnumSet.copyOf(granted);
        for (StandardCapability c : g) {
            if (state(c) != CapabilityState.SUPPORTED) {
                throw new IllegalArgumentException("verify-capabilities may only name SUPPORTED capabilities: " + c.id());
            }
            if (evidence.isEmpty() || platformVerified(c)) {
                throw new IllegalArgumentException("capability needs no verification grant: " + c.id());
            }
        }
        return new AdapterDescriptor(appId, displayName, version, strategy, protocolMin, protocolMax, riskLevel,
                capabilities, operations, resourceSource, web, evidence, g);
    }

    /** 平台路径上是否已有 live 证据（独立实例或正式实例）。 */
    public boolean platformVerified(StandardCapability c) {
        Evidence e = evidence.get(c);
        return e == Evidence.ISOLATED_LIVE || e == Evidence.LIVE;
    }

    /**
     * 网关是否开启该能力：状态必须 SUPPORTED；声明了 {@code evidence} 的适配器还要求平台路径有 live 证据，
     * 或操作者为验证窗口显式授权（ADR-0005）。未声明 evidence 的适配器保持 M1 语义。
     */
    public boolean enabled(StandardCapability c) {
        if (!state(c).enabledByDefault()) {
            return false;
        }
        return evidence.isEmpty() || platformVerified(c) || grants.contains(c);
    }

    public String profileId() {
        return appId + "/" + version;
    }

    public String namespace() {
        return appId.replace("-", "");
    }

    public boolean supportsCurrentProtocol() {
        ProtocolVersion cur = AdapterProtocol.CURRENT;
        return protocolMin.compareTo(cur) <= 0 && cur.compareTo(protocolMax) <= 0;
    }

    public CapabilityState state(StandardCapability c) {
        return capabilities.getOrDefault(c, CapabilityState.UNSUPPORTED);
    }

    /** 浏览器请求闸门：返回空表示放行。 */
    public Optional<ErrorCode> gateRequest(String operation) {
        if (operation == null || operation.length() > 64 || !Envelope.OPERATION_PATTERN.matcher(operation).matches()) {
            return Optional.of(ErrorCode.UNKNOWN_OPERATION);
        }
        if (!operation.startsWith(namespace() + ".")) {
            return Optional.of(ErrorCode.UNKNOWN_OPERATION);
        }
        OperationSpec spec = operations.get(operation);
        if (spec == null || spec.kind() != OperationKind.REQUEST) {
            return Optional.of(ErrorCode.UNKNOWN_OPERATION);
        }
        if (!enabled(spec.capability())) {
            return Optional.of(ErrorCode.CAPABILITY_DISABLED);
        }
        return Optional.empty();
    }

    /** 同步端点闸门：在 {@link #gateRequest} 之上还要求操作声明 {@code sync: true}。 */
    public Optional<ErrorCode> gateSyncRequest(String operation) {
        Optional<ErrorCode> g = gateRequest(operation);
        if (g.isPresent()) {
            return g;
        }
        return operations.get(operation).sync() ? Optional.empty() : Optional.of(ErrorCode.UNKNOWN_OPERATION);
    }

    /** 适配器推送闸门：只放行已声明且能力开启的事件。 */
    public boolean eventAllowed(String operation) {
        OperationSpec spec = operation == null ? null : operations.get(operation);
        return spec != null && spec.kind() == OperationKind.EVENT && enabled(spec.capability());
    }

    public boolean coalescible(String operation) {
        OperationSpec spec = operation == null ? null : operations.get(operation);
        return spec != null && spec.coalescible();
    }
}
