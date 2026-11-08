package io.github.dipinllx.macminiappbridge.api;

import io.github.dipinllx.macminiappbridge.identity.ConsoleSession;
import io.github.dipinllx.macminiappbridge.identity.InstanceSession;
import io.github.dipinllx.macminiappbridge.protocol.ErrorCode;
import io.github.dipinllx.macminiappbridge.web.ApiException;
import io.github.dipinllx.macminiappbridge.web.ListenerRegistry;
import io.github.dipinllx.macminiappbridge.web.MmabAttributes;
import java.time.Duration;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.web.server.ServerWebExchange;

/** 控制器读取 {@code GuardFilter} 写入的请求属性。 */
final class Requests {

    private Requests() {
    }

    static ListenerRegistry.Role role(ServerWebExchange ex) {
        ListenerRegistry.Role r = ex.getAttribute(MmabAttributes.ROLE);
        if (r == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND);
        }
        return r;
    }

    static ConsoleSession consoleSession(ServerWebExchange ex) {
        ConsoleSession s = ex.getAttribute(MmabAttributes.CONSOLE_SESSION);
        if (s == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHENTICATED);
        }
        return s;
    }

    static InstanceSession instanceSession(ServerWebExchange ex) {
        InstanceSession s = ex.getAttribute(MmabAttributes.INSTANCE_SESSION);
        if (s == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHENTICATED);
        }
        return s;
    }

    /** {@code __Host-} 前缀要求：Secure、Path=/、无 Domain（设计 §5.2 第 1 条）。 */
    static ResponseCookie sessionCookie(String name, String value) {
        return ResponseCookie.from(name, value).path("/").secure(true).httpOnly(true).sameSite("Strict").build();
    }

    static ResponseCookie expiredCookie(String name) {
        return ResponseCookie.from(name, "").path("/").secure(true).httpOnly(true).sameSite("Strict")
                .maxAge(Duration.ZERO).build();
    }
}
