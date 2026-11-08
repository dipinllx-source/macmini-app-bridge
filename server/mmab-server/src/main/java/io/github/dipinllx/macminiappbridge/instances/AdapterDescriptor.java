package io.github.dipinllx.macminiappbridge.instances;

import io.github.dipinllx.macminiappbridge.protocol.AdapterProtocol;
import io.github.dipinllx.macminiappbridge.protocol.CapabilityState;
import io.github.dipinllx.macminiappbridge.protocol.Envelope;
import io.github.dipinllx.macminiappbridge.protocol.ErrorCode;
import io.github.dipinllx.macminiappbridge.protocol.ProtocolVersion;
import io.github.dipinllx.macminiappbridge.protocol.StandardCapability;
import io.github.dipinllx.macminiappbridge.protocol.StrategyFamily;
import java.util.Map;
import java.util.Optional;

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
 */
public record AdapterDescriptor(String appId, String displayName, String version, StrategyFamily strategy,
        ProtocolVersion protocolMin, ProtocolVersion protocolMax, String riskLevel,
        Map<StandardCapability, CapabilityState> capabilities, Map<String, OperationSpec> operations,
        String resourceSource) {

    /** 平台保留的命名空间（控制帧），浏览器与适配器业务操作都不能使用。 */
    public static final String PLATFORM_NAMESPACE = "mmab";

    public enum OperationKind { REQUEST, EVENT }

    /**
     * @param name        操作名
     * @param capability  所属标准能力
     * @param kind        request（浏览器发起）/ event（适配器推送）
     * @param coalescible 仅 event：是否为可合并的状态通知（队列满时只保留最新值）
     */
    public record OperationSpec(String name, StandardCapability capability, OperationKind kind, boolean coalescible) {
    }

    public AdapterDescriptor {
        capabilities = Map.copyOf(capabilities);
        operations = Map.copyOf(operations);
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
        if (!state(spec.capability()).enabledByDefault()) {
            return Optional.of(ErrorCode.CAPABILITY_DISABLED);
        }
        return Optional.empty();
    }

    /** 适配器推送闸门：只放行已声明且能力 SUPPORTED 的事件。 */
    public boolean eventAllowed(String operation) {
        OperationSpec spec = operation == null ? null : operations.get(operation);
        return spec != null && spec.kind() == OperationKind.EVENT && state(spec.capability()).enabledByDefault();
    }

    public boolean coalescible(String operation) {
        OperationSpec spec = operation == null ? null : operations.get(operation);
        return spec != null && spec.coalescible();
    }
}
