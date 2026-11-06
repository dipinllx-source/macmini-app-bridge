package io.github.dipinllx.minimacappbridge.protocol;

/**
 * 协议常量与起始预算。预算取自设计文档 §9.3 中已验收的 Codex 原型边界，
 * 是起始值，不是所有应用的永久通用值；最终以各 profile 实测为准。
 */
public final class AdapterProtocol {

    /** 当前版本。 */
    public static final ProtocolVersion CURRENT = new ProtocolVersion(1, 0);

    /** 状态标记，供健康接口与握手展示：M1 离线实现，尚未接入真实应用。 */
    public static final String STATUS = "draft";

    /** 平台结构化值 codec（{@code sdk/protocol/codec-sv1.md}）。 */
    public static final String CODEC_VERSION = "mmab-sv1";

    /** 单帧上限：1 MiB（浏览器 WS 文本帧、适配器线上帧的 JSON 文本）。 */
    public static final int MAX_FRAME_BYTES = 1024 * 1024;

    /** 线上一行的上限：帧上限 + MAC / 包装余量。 */
    public static final int MAX_WIRE_LINE_BYTES = MAX_FRAME_BYTES + 64 * 1024;

    /** 认证完成前单行上限。 */
    public static final int MAX_HANDSHAKE_LINE_BYTES = 4 * 1024;

    /** 认证握手时限（毫秒）。 */
    public static final int HANDSHAKE_TIMEOUT_MILLIS = 3_000;

    /** 每连接入队上限：2048 帧。 */
    public static final int MAX_QUEUED_FRAMES = 2048;

    /** 每连接入队字节上限：8 MiB。 */
    public static final long MAX_QUEUED_BYTES = 8L * 1024 * 1024;

    /** 出站缓冲超过 4 MiB 视为慢客户端并断开。 */
    public static final long SLOW_CLIENT_BUFFERED_BYTES = 4L * 1024 * 1024;

    /** 控制 / 心跳保留额度：帧数。 */
    public static final int CONTROL_RESERVED_FRAMES = 64;

    /** 控制 / 心跳保留额度：字节。 */
    public static final long CONTROL_RESERVED_BYTES = 256L * 1024;

    /** 每连接待决 REQUEST 上限。 */
    public static final int MAX_PENDING_REQUESTS = 256;

    /** 每连接 MessagePort 上限（建议值）。 */
    public static final int MAX_PORTS_PER_CONNECTION = 256;

    /** 嵌套端口 / 结构深度上限（建议值）。 */
    public static final int MAX_NESTING_DEPTH = 32;

    /** MessagePort 闲置回收（秒，建议值）。 */
    public static final int PORT_IDLE_SECONDS = 300;

    private AdapterProtocol() {
    }
}
