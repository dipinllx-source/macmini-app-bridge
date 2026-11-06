package io.github.dipinllx.minimacappbridge.identity;

import io.github.dipinllx.minimacappbridge.config.MmabClock;
import io.github.dipinllx.minimacappbridge.config.MmabProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 一次性开窗票据（设计 §5.3）。
 *
 * <ul>
 *   <li>票据是 256 bit 随机值，服务端只存 SHA-256；默认 30 秒有效。</li>
 *   <li>绑定：控制台会话、实例、目标端口、目标 Origin、控制台 Origin、租约、fence、generation。</li>
 *   <li>exchange 时先原子移除再校验：无论成功与否，同一票据只能被提交一次（重放必败，
 *       并发双花只有一个调用者拿到票据）。</li>
 * </ul>
 */
@Component
public class LaunchTicketService {

    /** 被拒原因（只进审计，对外统一 {@code TICKET_REJECTED}，不给探测者区分信号）。 */
    public enum Rejection {
        UNKNOWN_OR_REPLAYED, EXPIRED, WRONG_PORT, WRONG_ORIGIN, WRONG_HOST
    }

    /** 票据记录（不含明文）。 */
    public record LaunchTicket(String instanceId, int targetPort, String targetOrigin, String consoleOrigin,
            String consoleSessionId, String leaseId, long fencingToken, long generation, Instant expiresAt) {
    }

    /** 签发结果：{@code token} 只返回给控制台页面一次。 */
    public record Issued(LaunchTicket ticket, String token) {
    }

    /** 兑换结果。 */
    public record Outcome(LaunchTicket ticket, Rejection rejection) {
        public boolean ok() {
            return rejection == null;
        }
    }

    private final Map<String, LaunchTicket> byHash = new ConcurrentHashMap<>();
    private final MmabClock clock;
    private final Duration ttl;

    public LaunchTicketService(MmabClock clock, MmabProperties properties) {
        this.clock = clock;
        this.ttl = properties.timeouts().ticketTtl();
    }

    public Issued issue(String instanceId, int targetPort, String targetOrigin, String consoleOrigin,
            String consoleSessionId, String leaseId, long fencingToken, long generation) {
        String token = Tokens.random();
        LaunchTicket t = new LaunchTicket(instanceId, targetPort, targetOrigin, consoleOrigin, consoleSessionId,
                leaseId, fencingToken, generation, clock.instant().plus(ttl));
        byHash.put(Tokens.sha256(token), t);
        return new Issued(t, token);
    }

    /**
     * 原子消费并校验。
     *
     * @param token         表单中的票据
     * @param presentedOrigin 请求的 Origin 头（必须恰为签发时的控制台 Origin）
     * @param localPort     接收请求的监听端口（必须是目标实例端口）
     * @param requestOrigin 由 scheme + Host 构成的本次请求目标 Origin（必须恰为票据目标 Origin）
     */
    public Outcome exchange(String token, String presentedOrigin, int localPort, String requestOrigin) {
        if (!Tokens.looksLikeToken(token)) {
            return new Outcome(null, Rejection.UNKNOWN_OR_REPLAYED);
        }
        LaunchTicket t = byHash.remove(Tokens.sha256(token));
        if (t == null) {
            return new Outcome(null, Rejection.UNKNOWN_OR_REPLAYED);
        }
        if (!clock.instant().isBefore(t.expiresAt())) {
            return new Outcome(t, Rejection.EXPIRED);
        }
        if (t.targetPort() != localPort) {
            return new Outcome(t, Rejection.WRONG_PORT);
        }
        if (!t.consoleOrigin().equals(presentedOrigin)) {
            return new Outcome(t, Rejection.WRONG_ORIGIN);
        }
        if (!t.targetOrigin().equals(requestOrigin)) {
            return new Outcome(t, Rejection.WRONG_HOST);
        }
        return new Outcome(t, null);
    }

    /** 清扫过期票据（只是回收内存；过期票据本来就兑换不了）。 */
    public void sweep() {
        Instant now = clock.instant();
        byHash.values().removeIf(t -> !now.isBefore(t.expiresAt()));
    }

    public int outstanding() {
        return byHash.size();
    }
}
