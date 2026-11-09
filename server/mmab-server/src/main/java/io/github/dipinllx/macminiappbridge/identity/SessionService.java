package io.github.dipinllx.macminiappbridge.identity;

import io.github.dipinllx.macminiappbridge.config.MmabClock;
import io.github.dipinllx.macminiappbridge.config.MmabProperties;
import io.github.dipinllx.macminiappbridge.leases.LeaseEvents;
import io.github.dipinllx.macminiappbridge.protocol.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 控制台会话与实例会话（设计 §6.1；reliable-personal-entry D3 / D4）。
 *
 * <ul>
 *   <li>服务端随机 token，只存散列；最长期限 {@code session-max-age}（默认 7 天），闲置期限 {@code session-idle}
 *       （默认等于最长期限）。</li>
 *   <li>会话持久化到本机数据库，平台重启后仍有效；租约与票据不持久化——实例会话重启后处于“未绑定”状态，
 *       下次访问时由 {@code InstanceAccess} 重新绑定租约。</li>
 *   <li>实例会话的活动同时为父控制台会话续期；续期写库按 {@link #PERSIST_EVERY} 节流。</li>
 *   <li>控制台注销级联撤销其派生的实例会话；租约撤销只解除绑定，不撤销会话。</li>
 * </ul>
 */
@Component
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);
    static final Duration PERSIST_EVERY = Duration.ofMinutes(5);

    /** 新建结果：{@code token} 只交给 Set-Cookie，一次性返回，不落日志。 */
    public record Created<T>(T session, String token) {
    }

    private final Map<String, ConsoleSession> consoleByHash = new ConcurrentHashMap<>();
    private final Map<String, InstanceSession> instanceByHash = new ConcurrentHashMap<>();
    private final MmabClock clock;
    private final Duration maxAge;
    private final Duration idle;
    private final ApplicationEventPublisher events;
    private final SessionStore store;

    public SessionService(MmabClock clock, MmabProperties properties, ApplicationEventPublisher events, SessionStore store) {
        this.clock = clock;
        this.maxAge = properties.timeouts().sessionMaxAge();
        this.idle = properties.timeouts().sessionIdle();
        this.events = events;
        this.store = store;
        restore();
    }

    private void restore() {
        Instant now = clock.instant();
        int consoles = 0;
        int instances = 0;
        for (SessionStore.ConsoleRow r : store.activeConsoles()) {
            if (!expired(r.createdAt(), r.lastSeen(), now)) {
                consoleByHash.put(r.tokenHash(),
                        new ConsoleSession(r.id(), r.tokenHash(), Tokens.random(), r.origin(), r.createdAt(), r.lastSeen()));
                consoles++;
            }
        }
        for (SessionStore.InstanceRow r : store.activeInstances()) {
            if (!expired(r.createdAt(), r.lastSeen(), now) && consoleById(r.parentId()).isPresent()) {
                instanceByHash.put(r.tokenHash(), new InstanceSession(r.id(), r.tokenHash(), r.instanceId(), r.port(),
                        r.parentId(), r.createdAt(), r.lastSeen()));
                instances++;
            }
        }
        if (consoles + instances > 0) {
            log.info("restored {} console and {} instance sessions (leases are re-acquired on next access)",
                    consoles, instances);
        }
    }

    public Duration maxAge() {
        return maxAge;
    }

    /** 会话剩余期限（用于 Cookie Max-Age）。 */
    public Duration remaining(Instant createdAt) {
        Duration left = Duration.between(clock.instant(), createdAt.plus(maxAge));
        return left.isNegative() ? Duration.ZERO : left;
    }

    public Created<ConsoleSession> createConsole(String origin) {
        String token = Tokens.random();
        ConsoleSession s = new ConsoleSession(Tokens.shortId("cs-"), Tokens.sha256(token), Tokens.random(), origin,
                clock.instant());
        store.saveConsole(s);
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
        touch(s, now);
        return Optional.of(s);
    }

    public Optional<ConsoleSession> consoleById(String id) {
        Instant now = clock.instant();
        return consoleByHash.values().stream()
                .filter(s -> s.id().equals(id) && !s.revoked() && !expired(s.createdAt(), s.lastSeen(), now))
                .findFirst();
    }

    /** 注销控制台会话，并级联撤销其派生的所有实例会话（设计 §10.2：管理 logout 级联撤销）。 */
    public void logoutConsole(ConsoleSession s, ErrorCode reason) {
        s.revoke();
        consoleByHash.remove(s.tokenHash());
        store.revoke(s.id(), true);
        for (InstanceSession i : List.copyOf(instanceByHash.values())) {
            if (i.parentSessionId().equals(s.id())) {
                revokeInstance(i, reason);
            }
        }
        events.publishEvent(new LeaseEvents.Revocation(null, null, null, s.id(), reason));
    }

    /** 注销全部会话（控制台“注销所有会话”）：返回被撤销的控制台会话。 */
    public List<ConsoleSession> revokeAll(ErrorCode reason) {
        List<ConsoleSession> all = List.copyOf(consoleByHash.values());
        for (ConsoleSession s : all) {
            logoutConsole(s, reason);
        }
        store.revokeAll();
        return all;
    }

    public Created<InstanceSession> createInstance(ConsoleSession parent, String instanceId, int port, String leaseId,
            long fencingToken, long generation) {
        String token = Tokens.random();
        InstanceSession s = new InstanceSession(Tokens.shortId("is-"), Tokens.sha256(token), instanceId, port,
                parent.id(), leaseId, fencingToken, generation, clock.instant());
        store.saveInstance(s);
        instanceByHash.put(s.tokenHash(), s);
        return new Created<>(s, token);
    }

    /**
     * 只校验会话本身（存在、未撤销、未过期、实例与端口绑定一致、父控制台会话仍有效）；租约 / generation 由
     * {@code InstanceAccess} 继续校验或重新绑定。实例会话的活动同时为父控制台会话续期。
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
        Optional<ConsoleSession> parent = consoleById(s.parentSessionId());
        if (parent.isEmpty()) {
            revokeInstance(s, ErrorCode.UNAUTHENTICATED);
            return Optional.empty();
        }
        touch(s, now);
        touch(parent.get(), now);
        return Optional.of(s);
    }

    /** 数据连接心跳：续期实例会话与父控制台会话（reliable-personal-entry D3）。 */
    public void touchActive(InstanceSession s) {
        if (s.revoked()) {
            return;
        }
        Instant now = clock.instant();
        touch(s, now);
        consoleById(s.parentSessionId()).ifPresent(p -> touch(p, now));
    }

    /** 绑定新租约（由 {@code InstanceAccess} 在重新获取租约后调用）。 */
    public void rebind(InstanceSession s, String leaseId, long fencingToken, long generation) {
        s.rebind(leaseId, fencingToken, generation);
    }

    public void revokeInstance(InstanceSession s, ErrorCode reason) {
        if (s.revoked()) {
            return;
        }
        s.revoke();
        instanceByHash.remove(s.tokenHash());
        store.revoke(s.id(), false);
        events.publishEvent(new LeaseEvents.Revocation(s.instanceId(), null, s.id(), null, reason));
    }

    /**
     * 租约被撤销（到期、generation 变化、适配器不可用、平台策略）：绑定该租约的实例会话解除绑定，
     * 数据连接由 {@code TransportRegistry} 关闭；会话本身保留，下次访问重新绑定。
     */
    @EventListener
    public void onRevocation(LeaseEvents.Revocation r) {
        if (r.leaseId() == null) {
            return;
        }
        for (InstanceSession i : instanceByHash.values()) {
            i.unbindIf(r.leaseId());
        }
    }

    public void sweep() {
        Instant now = clock.instant();
        consoleByHash.values().removeIf(s -> s.revoked() || expired(s.createdAt(), s.lastSeen(), now));
        for (InstanceSession i : List.copyOf(instanceByHash.values())) {
            if (expired(i.createdAt(), i.lastSeen(), now) || consoleById(i.parentSessionId()).isEmpty()) {
                revokeInstance(i, ErrorCode.UNAUTHENTICATED);
            }
        }
    }

    /** 清理库中已撤销 / 已过期的会话（维护任务，低频调用）。 */
    public int purgeStore() {
        Instant now = clock.instant();
        return store.purge(now.minus(maxAge), now.minus(idle));
    }

    public int activeInstanceSessions() {
        return instanceByHash.size();
    }

    public int activeConsoleSessions() {
        return consoleByHash.size();
    }

    private void touch(ConsoleSession s, Instant now) {
        s.touch(now);
        if (!now.isBefore(s.lastPersisted().plus(PERSIST_EVERY))) {
            s.persisted(now);
            store.touch(s.id(), true, now);
        }
    }

    private void touch(InstanceSession s, Instant now) {
        s.touch(now);
        if (!now.isBefore(s.lastPersisted().plus(PERSIST_EVERY))) {
            s.persisted(now);
            store.touch(s.id(), false, now);
        }
    }

    private boolean expired(Instant created, Instant lastSeen, Instant now) {
        return !now.isBefore(created.plus(maxAge)) || !now.isBefore(lastSeen.plus(idle));
    }
}
