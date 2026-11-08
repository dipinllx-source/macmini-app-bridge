package io.github.dipinllx.macminiappbridge.leases;

import io.github.dipinllx.macminiappbridge.config.MmabClock;
import io.github.dipinllx.macminiappbridge.config.MmabProperties;
import io.github.dipinllx.macminiappbridge.identity.Tokens;
import io.github.dipinllx.macminiappbridge.protocol.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * 每实例一条独占租约 + 单调 fencing token（设计 §9.1）。
 *
 * <p>原子性：{@link ConcurrentHashMap#compute} 在同一 key 上串行执行，竞争时只有一个调用者拿到新租约；
 * 其余得到 {@link ErrorCode#LEASE_OCCUPIED}。不做 last-writer-wins，不做隐式夺取。
 * M1 仅内存保存：进程重启即全部失效（设计 §10.1：重启时 fence/revoke，禁止恢复旧浏览器控制权）。
 */
@Component
public class LeaseManager {

    /** 获取结果：{@code lease} 非空即成功；{@code reused} 表示同一会话已持有、原样返回。 */
    public record AcquireResult(Lease lease, boolean reused, ErrorCode rejection) {
        public boolean ok() {
            return lease != null;
        }
    }

    private final Map<String, Lease> active = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> fenceCounters = new ConcurrentHashMap<>();
    private final MmabClock clock;
    private final Duration ttl;
    private final ApplicationEventPublisher events;

    public LeaseManager(MmabClock clock, MmabProperties properties, ApplicationEventPublisher events) {
        this.clock = clock;
        this.ttl = properties.timeouts().leaseTtl();
        this.events = events;
    }

    public AcquireResult acquire(String instanceId, String sessionId, long generation) {
        Instant now = clock.instant();
        AtomicReference<AcquireResult> result = new AtomicReference<>();
        AtomicReference<Lease> evicted = new AtomicReference<>();
        active.compute(instanceId, (id, current) -> {
            if (current != null && !current.expired(now) && current.generation() == generation) {
                if (current.holderSessionId().equals(sessionId)) {
                    result.set(new AcquireResult(current, true, null));
                } else {
                    result.set(new AcquireResult(null, false, ErrorCode.LEASE_OCCUPIED));
                }
                return current;
            }
            if (current != null) {
                evicted.set(current);
            }
            long fence = fenceCounters.computeIfAbsent(id, k -> new AtomicLong()).incrementAndGet();
            Lease lease = new Lease(Tokens.shortId("l-"), id, sessionId, fence, generation, now, now.plus(ttl));
            result.set(new AcquireResult(lease, false, null));
            return lease;
        });
        if (evicted.get() != null) {
            Lease old = evicted.get();
            events.publishEvent(new LeaseEvents.Revocation(old.instanceId(), old.leaseId(), null, null,
                    old.generation() != generation ? ErrorCode.STALE_GENERATION : ErrorCode.LEASE_REQUIRED));
        }
        if (result.get().ok() && !result.get().reused()) {
            events.publishEvent(new LeaseEvents.FenceChanged(instanceId));
        }
        return result.get();
    }

    /** 当前有效租约（未过期）。 */
    public Optional<Lease> current(String instanceId) {
        Lease l = active.get(instanceId);
        return l != null && !l.expired(clock.instant()) ? Optional.of(l) : Optional.empty();
    }

    /** 数据面 / 会话校验：租约 ID、fence、generation 必须与当前一致且未过期。 */
    public boolean isCurrent(String instanceId, String leaseId, long fencingToken, long generation) {
        return current(instanceId)
                .filter(l -> l.leaseId().equals(leaseId) && l.fencingToken() == fencingToken
                        && l.generation() == generation)
                .isPresent();
    }

    /**
     * 续租：只有持有会话、且 fence 一致时成功；否则返回拒绝原因。
     */
    public Optional<ErrorCode> heartbeat(String leaseId, String sessionId, Long fencingToken) {
        Instant now = clock.instant();
        Optional<Lease> found = findById(leaseId);
        if (found.isEmpty() || found.get().expired(now)) {
            return Optional.of(ErrorCode.STALE_GENERATION);
        }
        Lease l = found.get();
        if (!l.holderSessionId().equals(sessionId)) {
            return Optional.of(ErrorCode.LEASE_OCCUPIED);
        }
        if (fencingToken != null && fencingToken != l.fencingToken()) {
            return Optional.of(ErrorCode.STALE_GENERATION);
        }
        boolean[] renewed = {false};
        active.computeIfPresent(l.instanceId(), (id, cur) -> {
            if (cur.leaseId().equals(leaseId) && !cur.expired(now)) {
                renewed[0] = true;
                return cur.renewedUntil(now.plus(ttl));
            }
            return cur;
        });
        return renewed[0] ? Optional.empty() : Optional.of(ErrorCode.STALE_GENERATION);
    }

    /** 数据面活动续租（WS HEARTBEAT）：调用方已校验会话绑定。 */
    public boolean touch(String instanceId, String leaseId, long fencingToken) {
        Instant now = clock.instant();
        boolean[] renewed = {false};
        active.computeIfPresent(instanceId, (id, cur) -> {
            if (cur.leaseId().equals(leaseId) && cur.fencingToken() == fencingToken && !cur.expired(now)) {
                renewed[0] = true;
                return cur.renewedUntil(now.plus(ttl));
            }
            return cur;
        });
        return renewed[0];
    }

    /** 释放：只有持有会话可以释放。 */
    public boolean release(String leaseId, String sessionId, ErrorCode reason) {
        Optional<Lease> found = findById(leaseId);
        if (found.isEmpty() || !found.get().holderSessionId().equals(sessionId)) {
            return false;
        }
        return remove(found.get(), reason);
    }

    /** 撤销某实例的当前租约（generation 变化、兼容阻塞等）。 */
    public Optional<Lease> revokeInstance(String instanceId, ErrorCode reason) {
        Lease l = active.get(instanceId);
        if (l != null && remove(l, reason)) {
            return Optional.of(l);
        }
        return Optional.empty();
    }

    /** 控制台注销：释放该会话持有的全部租约。 */
    public List<Lease> releaseAllHeldBy(String sessionId, ErrorCode reason) {
        List<Lease> released = new ArrayList<>();
        for (Lease l : List.copyOf(active.values())) {
            if (l.holderSessionId().equals(sessionId) && remove(l, reason)) {
                released.add(l);
            }
        }
        return released;
    }

    /** 过期清扫：到期租约撤销并广播。 */
    public List<Lease> sweepExpired() {
        Instant now = clock.instant();
        List<Lease> expired = new ArrayList<>();
        for (Lease l : List.copyOf(active.values())) {
            if (l.expired(now) && remove(l, ErrorCode.LEASE_REQUIRED)) {
                expired.add(l);
            }
        }
        return expired;
    }

    public Optional<Lease> findById(String leaseId) {
        return active.values().stream().filter(l -> l.leaseId().equals(leaseId)).findFirst();
    }

    private boolean remove(Lease lease, ErrorCode reason) {
        boolean removed = active.remove(lease.instanceId(), lease);
        if (!removed) {
            // 可能已被续租替换为同 ID 的新对象：按 ID 再试一次
            Lease cur = active.get(lease.instanceId());
            removed = cur != null && cur.leaseId().equals(lease.leaseId()) && active.remove(lease.instanceId(), cur);
        }
        if (removed) {
            events.publishEvent(new LeaseEvents.Revocation(lease.instanceId(), lease.leaseId(), null, null, reason));
            events.publishEvent(new LeaseEvents.FenceChanged(lease.instanceId()));
        }
        return removed;
    }
}
