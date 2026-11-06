package io.github.dipinllx.minimacappbridge.protocol;

import java.time.Instant;
import java.util.Objects;

/**
 * 统一 envelope 草案（设计文档 §9.2）。Java 侧只校验 envelope 字段与预算，
 * {@code payload} 是 profile 专用 codec 编码后的不透明文本，不在 JVM 内按业务结构反序列化。
 *
 * @param protocolVersion   Adapter Protocol 版本
 * @param profileId         适配器 profile（应用 + 版本）
 * @param instanceId        桥实例 ID，例如 {@code qwen-01}
 * @param processGeneration App / renderer context / 注入重建时递增
 * @param connectionId      每次重连新建
 * @param leaseId           当前独占租约
 * @param fencingToken      租约 fence，单调递增
 * @param streamId          MessagePort / 流 ID；无则为 {@code null}
 * @param sequence          连接内单调序号
 * @param correlationId     请求-响应关联；无则为 {@code null}
 * @param messageKind       消息种类
 * @param deadline          过期即拒绝；迟到结果标"执行状态未知"
 * @param codecVersion      profile codec 版本
 * @param payload           不透明 payload（可为空串）
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
        Instant deadline,
        String codecVersion,
        String payload) {

    public Envelope {
        Objects.requireNonNull(protocolVersion, "protocolVersion");
        requireText(profileId, "profileId");
        requireText(instanceId, "instanceId");
        requireText(connectionId, "connectionId");
        requireText(leaseId, "leaseId");
        requireText(codecVersion, "codecVersion");
        Objects.requireNonNull(messageKind, "messageKind");
        Objects.requireNonNull(deadline, "deadline");
        Objects.requireNonNull(payload, "payload");
        if (processGeneration < 0 || fencingToken < 0 || sequence < 0) {
            throw new IllegalArgumentException("generation / fencingToken / sequence must be non-negative");
        }
        if (messageKind == MessageKind.RESPONSE && (correlationId == null || correlationId.isBlank())) {
            throw new IllegalArgumentException("RESPONSE requires correlationId");
        }
    }

    /** 是否已过 deadline。 */
    public boolean isExpired(Instant now) {
        return now.isAfter(deadline);
    }

    /** 粗略的帧预算检查（按 UTF-8 字节计）。 */
    public boolean exceedsFrameBudget() {
        return payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > AdapterProtocol.MAX_FRAME_BYTES;
    }

    /** 与当前实例代际 / fence 是否一致；不一致即 {@link ErrorCode#STALE_GENERATION}。 */
    public boolean matchesFence(long currentGeneration, long currentFencingToken) {
        return processGeneration == currentGeneration && fencingToken == currentFencingToken;
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
