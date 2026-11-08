package io.github.dipinllx.macminiappbridge.instances;

import io.github.dipinllx.macminiappbridge.identity.InstanceSession;
import io.github.dipinllx.macminiappbridge.identity.SessionService;
import io.github.dipinllx.macminiappbridge.leases.LeaseManager;
import io.github.dipinllx.macminiappbridge.protocol.ErrorCode;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 实例端口访问判定（设计 §5.3 第 5 条）：实例 Cookie 对应的会话有效 + 绑定本端口 + 租约仍是当前租约 +
 * generation 未变化。任何一项不满足都当作"没有会话"。
 */
@Component
public class InstanceAccess {

    private final SessionService sessions;
    private final LeaseManager leases;
    private final InstanceRegistry instances;

    public InstanceAccess(SessionService sessions, LeaseManager leases, InstanceRegistry instances) {
        this.sessions = sessions;
        this.leases = leases;
        this.instances = instances;
    }

    public Optional<InstanceSession> resolve(String token, String instanceId, int port) {
        return sessions.resolveInstance(token, instanceId, port).filter(s -> check(s) == null);
    }

    /** @return null 表示仍有效；否则为失效原因 */
    public ErrorCode check(InstanceSession s) {
        if (s.revoked()) {
            return ErrorCode.UNAUTHENTICATED;
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
