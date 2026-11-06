package io.github.dipinllx.minimacappbridge.protocol;

/**
 * 结构化错误码。前 8 项为设计文档 §10.2 要求的最小集合，其余为 Adapter Protocol v1 补充。
 * 错误码只描述类别，不携带正文、路径、凭据或带参数 URL（设计 §6.3 审计要求）。
 *
 * <p>与 {@code sdk/protocol/error-codes.json} 保持一致（契约测试校验）。
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

    // --- Adapter Protocol v1 补充 ---
    PROTOCOL_VERSION_UNSUPPORTED("Adapter Protocol major 版本不受支持"),
    UNAUTHENTICATED("缺少或无效的服务身份 / 会话"),
    ORIGIN_REJECTED("Origin 缺失、为 null 或不精确匹配"),
    UNKNOWN_OPERATION("不在 profile 白名单内的操作"),
    CAPABILITY_DISABLED("能力状态不是 SUPPORTED，网关拒绝对应通道"),
    DEADLINE_EXCEEDED("超过请求 deadline；执行状态未知"),
    BLOCKED_UPGRADE("应用已升级而无兼容 adapter，只观察"),
    MALFORMED_FRAME("帧不是合法 JSON 或不符合协议 schema"),
    LEASE_REQUIRED("没有当前有效的独占租约"),
    CSRF_REJECTED("缺少或错误的 CSRF token"),
    FETCH_SITE_REJECTED("敏感资源只允许同源加载（Sec-Fetch-Site）"),
    HOST_REJECTED("Host 不是该监听登记的地址"),
    TICKET_REJECTED("开窗票据无效、已使用、已过期或绑定不符"),
    REPLAY_REJECTED("线上帧认证码或序号不符，按重放处理"),
    ADAPTER_UNAVAILABLE("适配器进程未就绪"),
    PORT_STATE("MessagePort 状态不允许该操作（未知 / 已关闭 / 已转移）"),
    RATE_LIMITED("认证失败过多，稍后再试"),
    SETUP_REQUIRED("平台操作者口令尚未初始化"),
    NOT_FOUND("资源不存在或不属于该监听"),
    METHOD_NOT_ALLOWED("该监听不接受此方法");

    private final String summary;

    ErrorCode(String summary) {
        this.summary = summary;
    }

    /** 面向操作者的简短中文说明。 */
    public String summary() {
        return summary;
    }
}
