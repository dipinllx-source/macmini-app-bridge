package io.github.dipinllx.macminiappbridge.protocol;

/**
 * 分层健康模型（设计文档 §8）。监听不等于能用：每层独立记录 verifiedAt，
 * 不用单一绿色 healthy 掩盖 L4 未验证。
 */
public enum HealthLevel {
    L0_PROCESS,
    L1_LISTENER,
    L2_BRIDGE,
    L3_RENDER,
    /** 默认人工触发，不定时自动执行。 */
    L4_BUSINESS
}
