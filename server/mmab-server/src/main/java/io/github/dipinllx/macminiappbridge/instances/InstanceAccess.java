package io.github.dipinllx.macminiappbridge.instances;

import io.github.dipinllx.macminiappbridge.identity.InstanceSession;
import io.github.dipinllx.macminiappbridge.identity.SessionService;
import io.github.dipinllx.macminiappbridge.leases.LeaseManager;
import io.github.dipinllx.macminiappbridge.protocol.ErrorCode;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 实例端口访问判定（设计 §5.3 第 5 条）：实例 Cookie 对应的会话有效 + 绑定本端口 + 租约仍是当前租约 +
 * generation 未变化。
 *
 * <p>reliable-personal-entry D3/D4：租约到期、平台重启或 generation 变化后，会话本身仍有效时，以父控制台会话
 * 的身份重新获取租约并绑定（新的 fencing token；旧连接与旧帧仍被 fence 拒绝）。租约被其他控制台会话持有时不绑定。
 */
@Component
public class InstanceAccess {

    private final SessionService sessions;
    private final LeaseManager leases;
    private final InstanceRegistry instances;

    private io.github.dipinllx.macminiappbridge.proxy.TransportRegistry transports;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setTransports(io.github.dipinllx.macminiappbridge.proxy.TransportRegistry transports) {
        this.transports = transports;
    }

    public InstanceAccess(SessionService sessions, LeaseManager leases, InstanceRegistry instances) {
        this.sessions = sessions;
        this.leases = leases;
        this.instances = instances;
    }

    public Optional<InstanceSession> resolve(String token, String instanceId, int port) {
        return sessions.resolveInstance(token, instanceId, port).filter(s -> ensureBound(s) == null);
    }

    /** 带原因的判定：会话有效但租约被他人持有时返回 {@code LEASE_OCCUPIED}（页面据此提示，而不是要求重新登录）。 */
    public record Resolution(InstanceSession session, ErrorCode reason) {
    }

    public Resolution resolveDetailed(String token, String instanceId, int port) {
        Optional<InstanceSession> s = sessions.resolveInstance(token, instanceId, port);
        if (s.isEmpty()) {
            return new Resolution(null, ErrorCode.UNAUTHENTICATED);
        }
        ErrorCode reason = ensureBound(s.get());
        return reason == null ? new Resolution(s.get(), null) : new Resolution(null, reason);
    }

    /**
     * 确保会话绑定到当前租约：已绑定且有效直接返回 null；否则尝试为其父控制台会话重新获取租约。
     *
     * @return null 表示可用；否则为不可用原因（如 {@code LEASE_OCCUPIED}）
     */
    public ErrorCode ensureBound(InstanceSession s) {
        ErrorCode c = check(s);
        if (c == null || c == ErrorCode.UNAUTHENTICATED) {
            return c;
        }
        LeaseManager.AcquireResult r = leases.acquire(s.instanceId(), s.parentSessionId(),
                instances.generation(s.instanceId()));
        if (!r.ok() && r.rejection() == ErrorCode.LEASE_OCCUPIED && transports != null
                && transports.yieldSuspended(s.instanceId(), leases)) {
            r = leases.acquire(s.instanceId(), s.parentSessionId(), instances.generation(s.instanceId()));
        }
        if (!r.ok()) {
            return r.rejection();
        }
        sessions.rebind(s, r.lease().leaseId(), r.lease().fencingToken(), r.lease().generation());
        return check(s);
    }

    /** 数据连接上的活动（心跳）：续期实例会话与父控制台会话。 */
    public void touchActive(InstanceSession s) {
        sessions.touchActive(s);
    }

    /** 已建立的数据连接：会话未撤销，且连接建立时的租约绑定仍是当前租约。 */
    public ErrorCode check(InstanceSession s, String leaseId, long fencingToken, long generation) {
        if (s.revoked()) {
            return ErrorCode.UNAUTHENTICATED;
        }
        if (generation != instances.generation(s.instanceId())) {
            return ErrorCode.STALE_GENERATION;
        }
        if (leaseId == null || !leases.isCurrent(s.instanceId(), leaseId, fencingToken, generation)) {
            return ErrorCode.LEASE_REQUIRED;
        }
        return null;
    }

    /** @return null 表示仍有效；否则为失效原因 */
    public ErrorCode check(InstanceSession s) {
        if (s.revoked()) {
            return ErrorCode.UNAUTHENTICATED;
        }
        if (!s.bound()) {
            return ErrorCode.LEASE_REQUIRED;
        }
        if (s.generation() != instances.generation(s.instanceId())) {
            return ErrorCode.STALE_GENERATION;
        }
        if (!leases.isCurrent(s.instanceId(), s.leaseId(), s.fencingToken(), s.generation())) {
            return ErrorCode.LEASE_REQUIRED;
        }
        return null;
    }
}
