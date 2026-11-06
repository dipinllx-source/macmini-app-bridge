package io.github.dipinllx.minimacappbridge.identity;

import io.github.dipinllx.minimacappbridge.config.MmabClock;
import io.github.dipinllx.minimacappbridge.config.MmabProperties;
import io.github.dipinllx.minimacappbridge.leases.LeaseEvents;
import io.github.dipinllx.minimacappbridge.protocol.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 控制台会话与实例会话（设计 §6.1）：服务端随机 token、只存 hash、最长 8 小时、闲置 30 分钟；
 * 进程重启全部失效（只在内存）。控制台注销级联撤销其派生的实例会话。
 */
@Component
public class SessionService {

    /** 新建结果：{@code token} 只交给 Set-Cookie，一次性返回，不落日志。 */
    public record Created<T>(T session, String token) {
    }

    private final Map<String, ConsoleSession> consoleByHash = new ConcurrentHashMap<>();
    private final Map<String, InstanceSession> instanceByHash = new ConcurrentHashMap<>();
    private final MmabClock clock;
    private final Duration maxAge;
    private final Duration idle;
    private final ApplicationEventPublisher events;

    public SessionService(MmabClock clock, MmabProperties properties, ApplicationEventPublisher events) {
        this.clock = clock;
        this.maxAge = properties.timeouts().sessionMaxAge();
        this.idle = properties.timeouts().sessionIdle();
        this.events = events;
    }

    public Created<ConsoleSession> createConsole(String origin) {
        String token = Tokens.random();
        ConsoleSession s = new ConsoleSession(Tokens.shortId("cs-"), Tokens.sha256(token), Tokens.random(), origin,
                clock.instant());
        consoleByHash.put(s.tokenHash(), s);
        return new Created<>(s, token);
    }

    public Optional<ConsoleSession> resolveConsole(String token) {
        if (!Tokens.looksLikeToken(token)) {
            return Optional.empty();
        }
        ConsoleSession s = consoleByHash.get(Tokens.sha256(token));
        Instant now = clock.instant();
        if (s == null || s.revoked() || expired(s.createdAt(), s.lastSeen(), now)) {
            return Optional.empty();
        }
        s.touch(now);
        return Optional.of(s);
    }

    public Optional<ConsoleSession> consoleById(String id) {
        return consoleByHash.values().stream().filter(s -> s.id().equals(id) && !s.revoked()).findFirst();
    }

    /** 注销控制台会话，并级联撤销其派生的所有实例会话（设计 §10.2：管理 logout 级联撤销）。 */
    public void logoutConsole(ConsoleSession s, ErrorCode reason) {
        s.revoke();
        consoleByHash.remove(s.tokenHash());
        for (InstanceSession i : List.copyOf(instanceByHash.values())) {
            if (i.parentSessionId().equals(s.id())) {
                revokeInstance(i, reason);
            }
        }
        events.publishEvent(new LeaseEvents.Revocation(null, null, null, s.id(), reason));
    }

    public Created<InstanceSession> createInstance(ConsoleSession parent, String instanceId, int port, String leaseId,
            long fencingToken, long generation) {
        String token = Tokens.random();
        InstanceSession s = new InstanceSession(Tokens.shortId("is-"), Tokens.sha256(token), instanceId, port,
                parent.id(), leaseId, fencingToken, generation, clock.instant());
        instanceByHash.put(s.tokenHash(), s);
        return new Created<>(s, token);
    }

    /**
     * 只校验会话本身（存在、未撤销、未过期、实例与端口绑定一致）；租约 / generation 由
     * {@code InstanceAccess} 继续校验。
     */
    public Optional<InstanceSession> resolveInstance(String token, String instanceId, int port) {
        if (!Tokens.looksLikeToken(token)) {
            return Optional.empty();
        }
        InstanceSession s = instanceByHash.get(Tokens.sha256(token));
        Instant now = clock.instant();
        if (s == null || s.revoked() || expired(s.createdAt(), s.lastSeen(), now)) {
            return Optional.empty();
        }
        if (!s.instanceId().equals(instanceId) || s.port() != port) {
            return Optional.empty();
        }
        s.touch(now);
        return Optional.of(s);
    }

    public void revokeInstance(InstanceSession s, ErrorCode reason) {
        if (s.revoked()) {
            return;
        }
        s.revoke();
        instanceByHash.remove(s.tokenHash());
        events.publishEvent(new LeaseEvents.Revocation(s.instanceId(), null, s.id(), null, reason));
    }

    /** 租约被撤销：绑定该租约的实例会话一并失效。 */
    @EventListener
    public void onRevocation(LeaseEvents.Revocation r) {
        if (r.leaseId() == null) {
            return;
        }
        for (InstanceSession i : List.copyOf(instanceByHash.values())) {
            if (i.leaseId().equals(r.leaseId())) {
                revokeInstance(i, r.reason());
            }
        }
    }

    public void sweep() {
        Instant now = clock.instant();
        consoleByHash.values().removeIf(s -> s.revoked() || expired(s.createdAt(), s.lastSeen(), now));
        for (InstanceSession i : List.copyOf(instanceByHash.values())) {
            if (expired(i.createdAt(), i.lastSeen(), now)) {
                revokeInstance(i, ErrorCode.UNAUTHENTICATED);
            }
        }
    }

    public int activeInstanceSessions() {
        return instanceByHash.size();
    }

    private boolean expired(Instant created, Instant lastSeen, Instant now) {
        return !now.isBefore(created.plus(maxAge)) || !now.isBefore(lastSeen.plus(idle));
    }
}
