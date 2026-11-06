package io.github.dipinllx.minimacappbridge.protocol;

/**
 * envelope 的消息种类。数据与端口控制字段分离，防止类型标记碰撞（设计 §9.4）。
 */
public enum MessageKind {
    /** 有 correlationId 的请求。 */
    REQUEST,
    /** 对应 REQUEST 的响应。 */
    RESPONSE,
    /** 宿主推送 / 流式事件。 */
    EVENT,
    /** MessagePort 打开。 */
    PORT_OPEN,
    /** MessagePort 所有权转移。 */
    PORT_TRANSFER,
    /** MessagePort 数据帧。 */
    PORT_DATA,
    /** MessagePort 关闭。 */
    PORT_CLOSE,
    /** MessagePort 错误。 */
    PORT_ERROR,
    /** 心跳（保留额度，不可被大消息饿死）。 */
    HEARTBEAT,
    /** 连接级控制（握手、credit、关闭原因）。 */
    CONTROL
}
