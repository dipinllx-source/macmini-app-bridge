package io.github.dipinllx.minimacappbridge.leases;

import io.github.dipinllx.minimacappbridge.protocol.ErrorCode;

/** 租约 / 会话撤销与 fence 变化事件（同步发布）。 */
public final class LeaseEvents {

    private LeaseEvents() {
    }

    /**
     * 撤销：匹配的数据面连接必须立即关闭、待决调用取消（设计 §9.1）。
     * 字段为 {@code null} 表示该维度不限定；全部为空时作用于整个实例。
     */
    public record Revocation(String instanceId, String leaseId, String instanceSessionId, String consoleSessionId,
            ErrorCode reason) {
    }

    /** 实例的 (generation, lease, fence) 发生变化，需要同步给 adapter。 */
    public record FenceChanged(String instanceId) {
    }
}
