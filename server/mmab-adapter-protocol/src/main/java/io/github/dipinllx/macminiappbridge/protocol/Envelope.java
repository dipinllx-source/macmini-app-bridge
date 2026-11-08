package io.github.dipinllx.macminiappbridge.protocol;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Adapter Protocol v1 envelope（设计文档 §9.2）。Java 侧只校验 envelope 字段与预算，
 * {@code payload} 是 profile codec 编码后的不透明文本，不在 JVM 内按业务结构反序列化。
 * 语言无关定义见 {@code sdk/protocol/envelope.schema.json}。
 *
 * @param protocolVersion   Adapter Protocol 版本
 * @param profileId         适配器 profile（应用 + 版本）
 * @param instanceId        桥实例 ID，例如 {@code qwen-01}
 * @param processGeneration App / renderer context / adapter 重建时递增
 * @param connectionId      浏览器连接 ID，每次重连新建；平台控制帧用 {@link #PLATFORM_CONNECTION}
 * @param leaseId           当前独占租约；无租约时为 {@link #NO_LEASE}
 * @param fencingToken      租约 fence，单调递增；无租约时为 0
 * @param streamId          MessagePort / 流 ID；无则为 {@code null}
 * @param sequence          连接内单调序号
 * @param correlationId     请求-响应关联；无则为 {@code null}
 * @param messageKind       消息种类
 * @param operation         REQUEST / CONTROL 必填的操作名（profile 白名单）；EVENT 可选
 * @param deadline          过期即拒绝；迟到结果标"执行状态未知"
 * @param codecVersion      profile codec 版本
 * @param payload           不透明 payload（可为空串）
 * @param error             RESPONSE / CONTROL / PORT_ERROR 可携带的结构化错误；否则为 {@code null}
 */
public record Envelope(
        ProtocolVersion protocolVersion,
        String profileId,
        String instanceId,
        long processGeneration,
        String connectionId,
        String leaseId,
        long fencingToken,
        String streamId,
        long sequence,
        String correlationId,
        MessageKind messageKind,
        String operation,
        Instant deadline,
        String codecVersion,
        String payload,
        EnvelopeError error) {

    /** 无租约时的 leaseId 占位。 */
    public static final String NO_LEASE = "none";

    /** 平台 ↔ 适配器控制帧使用的 connectionId。 */
    public static final String PLATFORM_CONNECTION = "platform";

    /** 操作名：小写命名空间 + 点分段，例如 {@code mock.echo}、{@code mmab.fence}。 */
    public static final Pattern OPERATION_PATTERN = Pattern.compile("^[a-z][a-z0-9]*(\\.[a-z][a-zA-Z0-9-]*)+$");

    /** 标识符（instanceId、connectionId、leaseId 等）允许的字符。 */
    public static final Pattern ID_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$");

    public Envelope {
        Objects.requireNonNull(protocolVersion, "protocolVersion");
        requireId(profileId, "profileId");
        requireId(instanceId, "instanceId");
        requireId(connectionId, "connectionId");
        requireId(leaseId, "leaseId");
        requireText(codecVersion, "codecVersion");
        Objects.requireNonNull(messageKind, "messageKind");
        Objects.requireNonNull(deadline, "deadline");
        Objects.requireNonNull(payload, "payload");
        if (streamId != null) {
            requireId(streamId, "streamId");
        }
        if (correlationId != null) {
            requireId(correlationId, "correlationId");
        }
        if (processGeneration < 0 || fencingToken < 0 || sequence < 0) {
            throw new IllegalArgumentException("generation / fencingToken / sequence must be non-negative");
        }
        if (messageKind == MessageKind.RESPONSE && correlationId == null) {
            throw new IllegalArgumentException("RESPONSE requires correlationId");
        }
        if (messageKind == MessageKind.REQUEST && correlationId == null) {
            throw new IllegalArgumentException("REQUEST requires correlationId");
        }
        boolean opRequired = messageKind == MessageKind.REQUEST || messageKind == MessageKind.CONTROL;
        if (operation == null) {
            if (opRequired) {
                throw new IllegalArgumentException(messageKind + " requires operation");
            }
        } else if (operation.length() > 64 || !OPERATION_PATTERN.matcher(operation).matches()) {
            throw new IllegalArgumentException("invalid operation name");
        }
        if (error != null && messageKind != MessageKind.RESPONSE && messageKind != MessageKind.CONTROL
                && messageKind != MessageKind.PORT_ERROR) {
            throw new IllegalArgumentException("error only allowed on RESPONSE / CONTROL / PORT_ERROR");
        }
    }

    /** 是否已过 deadline。 */
    public boolean isExpired(Instant now) {
        return now.isAfter(deadline);
    }

    /** payload 的帧预算检查（按 UTF-8 字节计）。 */
    public boolean exceedsFrameBudget() {
        return payload.getBytes(StandardCharsets.UTF_8).length > AdapterProtocol.MAX_FRAME_BYTES;
    }

    /** 与当前实例代际 / 租约 / fence 是否一致；不一致即 {@link ErrorCode#STALE_GENERATION}。 */
    public boolean matchesFence(long currentGeneration, String currentLeaseId, long currentFencingToken) {
        return processGeneration == currentGeneration && leaseId.equals(currentLeaseId)
                && fencingToken == currentFencingToken;
    }

    /** 只比较 generation 与 fence（兼容草案 API）。 */
    public boolean matchesFence(long currentGeneration, long currentFencingToken) {
        return processGeneration == currentGeneration && fencingToken == currentFencingToken;
    }

    /** 构造对该请求的错误响应，沿用 instance / generation / lease / connection / correlation。 */
    public Envelope errorResponse(ErrorCode code, long responseSequence) {
        return toBuilder().messageKind(MessageKind.RESPONSE).sequence(responseSequence)
                .correlationId(correlationId != null ? correlationId : "none")
                .operation(operation).payload("").error(EnvelopeError.of(code)).build();
    }

    public Builder toBuilder() {
        return new Builder().protocolVersion(protocolVersion).profileId(profileId).instanceId(instanceId)
                .processGeneration(processGeneration).connectionId(connectionId).leaseId(leaseId)
                .fencingToken(fencingToken).streamId(streamId).sequence(sequence).correlationId(correlationId)
                .messageKind(messageKind).operation(operation).deadline(deadline).codecVersion(codecVersion)
                .payload(payload).error(error);
    }

    public static Builder builder() {
        return new Builder();
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static void requireId(String value, String name) {
        requireText(value, name);
        if (!ID_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " has invalid characters");
        }
    }

    /** 可变构造器；{@link #build()} 时执行全部不变量校验。 */
    public static final class Builder {
        private ProtocolVersion protocolVersion = AdapterProtocol.CURRENT;
        private String profileId;
        private String instanceId;
        private long processGeneration;
        private String connectionId = PLATFORM_CONNECTION;
        private String leaseId = NO_LEASE;
        private long fencingToken;
        private String streamId;
        private long sequence;
        private String correlationId;
        private MessageKind messageKind;
        private String operation;
        private Instant deadline;
        private String codecVersion = AdapterProtocol.CODEC_VERSION;
        private String payload = "";
        private EnvelopeError error;

        public Builder protocolVersion(ProtocolVersion v) { this.protocolVersion = v; return this; }
        public Builder profileId(String v) { this.profileId = v; return this; }
        public Builder instanceId(String v) { this.instanceId = v; return this; }
        public Builder processGeneration(long v) { this.processGeneration = v; return this; }
        public Builder connectionId(String v) { this.connectionId = v; return this; }
        public Builder leaseId(String v) { this.leaseId = v; return this; }
        public Builder fencingToken(long v) { this.fencingToken = v; return this; }
        public Builder streamId(String v) { this.streamId = v; return this; }
        public Builder sequence(long v) { this.sequence = v; return this; }
        public Builder correlationId(String v) { this.correlationId = v; return this; }
        public Builder messageKind(MessageKind v) { this.messageKind = v; return this; }
        public Builder operation(String v) { this.operation = v; return this; }
        public Builder deadline(Instant v) { this.deadline = v; return this; }
        public Builder codecVersion(String v) { this.codecVersion = v; return this; }
        public Builder payload(String v) { this.payload = v; return this; }
        public Builder error(EnvelopeError v) { this.error = v; return this; }

        public Envelope build() {
            return new Envelope(protocolVersion, profileId, instanceId, processGeneration, connectionId, leaseId,
                    fencingToken, streamId, sequence, correlationId, messageKind, operation, deadline, codecVersion,
                    payload, error);
        }
    }
}
