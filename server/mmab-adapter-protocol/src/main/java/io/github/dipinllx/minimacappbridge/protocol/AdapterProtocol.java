package io.github.dipinllx.minimacappbridge.protocol;

/**
 * 协议常量与起始预算。预算取自设计文档 §9.3 中已验收的 Codex 原型边界，
 * 是起始值，不是所有应用的永久通用值；最终以各 profile 实测为准。
 */
public final class AdapterProtocol {

    /** 当前草案版本。 */
    public static final ProtocolVersion CURRENT = new ProtocolVersion(1, 0);

    /** 草案状态标记，供健康接口与握手展示。 */
    public static final String STATUS = "draft";

    /** 单帧上限：1 MiB。 */
    public static final int MAX_FRAME_BYTES = 1024 * 1024;

    /** 每连接入队上限：2048 帧。 */
    public static final int MAX_QUEUED_FRAMES = 2048;

    /** 每连接入队字节上限：8 MiB。 */
    public static final long MAX_QUEUED_BYTES = 8L * 1024 * 1024;

    /** 出站缓冲超过 4 MiB 视为慢客户端并断开。 */
    public static final long SLOW_CLIENT_BUFFERED_BYTES = 4L * 1024 * 1024;

    /** 每连接 MessagePort 上限（建议值）。 */
    public static final int MAX_PORTS_PER_CONNECTION = 256;

    /** 嵌套端口 / 结构深度上限（建议值）。 */
    public static final int MAX_NESTING_DEPTH = 32;

    private AdapterProtocol() {
    }
}
