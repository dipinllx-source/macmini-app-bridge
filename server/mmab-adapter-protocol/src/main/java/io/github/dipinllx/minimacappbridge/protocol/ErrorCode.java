package io.github.dipinllx.minimacappbridge.protocol;

/**
 * 结构化错误码草案。前 8 项为设计文档 §10.2 要求的最小集合，其余为本草案补充。
 * 错误码只描述类别，不携带正文、路径、凭据或带参数 URL（设计 §6.3 审计要求）。
 */
public enum ErrorCode {
    // --- 设计文档 §10.2 最小集合 ---
    COMPATIBILITY_BLOCKED("应用版本 / 指纹与已批准 profile 不匹配"),
    PORT_OWNER_MISMATCH("监听端口归属与登记不一致"),
    WAIT_CONFIRM("需要操作者确认后才能继续"),
    LEASE_OCCUPIED("实例独占租约已被占用"),
    STALE_GENERATION("消息属于已失效的 generation / 租约"),
    CODEC_UNSUPPORTED("payload 含当前 codec 不支持的类型"),
    QUEUE_LIMIT("超出帧 / 字节 / 端口 / 待决调用预算"),
    INSPECTOR_CLEANUP_FAILED("临时 inspector 关闭失败，需人工处理"),

    // --- 草案补充 ---
    PROTOCOL_VERSION_UNSUPPORTED("Adapter Protocol major 版本不受支持"),
    UNAUTHENTICATED("缺少或无效的服务身份 / 会话"),
    ORIGIN_REJECTED("Origin 缺失、为 null 或不精确匹配"),
    UNKNOWN_OPERATION("不在 profile 白名单内的操作"),
    CAPABILITY_DISABLED("能力状态不是 SUPPORTED，网关拒绝对应通道"),
    DEADLINE_EXCEEDED("超过请求 deadline；执行状态未知"),
    BLOCKED_UPGRADE("应用已升级而无兼容 adapter，只观察");

    private final String summary;

    ErrorCode(String summary) {
        this.summary = summary;
    }

    /** 面向操作者的简短中文说明。 */
    public String summary() {
        return summary;
    }
}
