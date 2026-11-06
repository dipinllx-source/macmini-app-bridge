package org.springframework.web.reactive.socket.adapter;

import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.netty.Connection;

/**
 * MMAB：立即断开底层 Netty 连接（慢客户端不读数据时，关闭帧本身也发不出去）。
 *
 * <p>放在 Spring 的包里只是为了访问 {@code AbstractWebSocketSession#getDelegate()}（protected）；
 * Spring 未提供公开的"中止连接"API。升级 Spring 时需回归 {@code SlowClientIsolation} 相关测试。
 */
public final class MmabSocketAbort {

    private MmabSocketAbort() {
    }

    /** @return true 表示已对 Reactor Netty 连接发出 dispose */
    public static boolean abort(WebSocketSession session) {
        if (session instanceof ReactorNettyWebSocketSession rn) {
            rn.getDelegate().getInbound().withConnection(Connection::dispose);
            return true;
        }
        return false;
    }
}
