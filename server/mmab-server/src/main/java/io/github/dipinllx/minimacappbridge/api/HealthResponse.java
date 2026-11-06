package io.github.dipinllx.minimacappbridge.api;

/**
 * 平台健康接口响应。只描述平台自身，不包含实例端口、调试端口、PID 或任何账号信息。
 *
 * @param platform            平台名
 * @param shortName           简称
 * @param version             平台构建版本
 * @param status              生命周期阶段；骨架阶段恒为 {@code design-skeleton}
 * @param adapterProtocol     Adapter Protocol 版本与状态
 * @param registeredInstances 已登记实例数（骨架阶段均未启用）
 * @param note                面向操作者的说明
 */
public record HealthResponse(
        String platform,
        String shortName,
        String version,
        String status,
        String adapterProtocol,
        int registeredInstances,
        String note) {
}
