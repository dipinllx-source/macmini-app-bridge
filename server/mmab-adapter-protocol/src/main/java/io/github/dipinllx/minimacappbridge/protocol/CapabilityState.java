package io.github.dipinllx.minimacappbridge.protocol;

/**
 * 能力四态（设计文档 §14.4）。未验收能力默认关闭，网关拒绝对应通道。
 */
public enum CapabilityState {
    /** 有当前应用版本的业务证据。 */
    SUPPORTED,
    /** 未验收；默认关闭，界面显示"未验证"。 */
    UNVERIFIED,
    /** 平台或操作者显式关闭。 */
    DISABLED,
    /** 该应用 / 版本不支持或不适用。 */
    UNSUPPORTED;

    /** 只有 SUPPORTED 默认放行。 */
    public boolean enabledByDefault() {
        return this == SUPPORTED;
    }
}
