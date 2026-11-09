package io.github.dipinllx.macminiappbridge.identity;

import java.time.Instant;

/**
 * 实例端口会话：只在签发它的端口有效，绑定实例、端口、父控制台会话、租约、fence 与 generation
 * （设计 §5.2 第 4 条、§5.3 第 5 条）。任何一项失效即整体失效。
 */
public final class InstanceSession {

    private final String id;
    private final String tokenHash;
    private final String instanceId;
    private final int port;
    private final String parentSessionId;
    /** 当前租约绑定；租约到期 / 平台重启 / generation 变化后为 {@link #UNBOUND}，下次访问时重新绑定。 */
    private record Binding(String leaseId, long fencingToken, long generation) {
    }

    private static final Binding UNBOUND = new Binding(null, -1, -1);

    private volatile Binding binding;
    private final Instant createdAt;
    private volatile Instant lastSeen;
    private volatile Instant lastPersisted;
    private volatile boolean revoked;

    InstanceSession(String id, String tokenHash, String instanceId, int port, String parentSessionId, String leaseId,
            long fencingToken, long generation, Instant createdAt) {
        this(id, tokenHash, instanceId, port, parentSessionId, new Binding(leaseId, fencingToken, generation), createdAt,
                createdAt);
    }

    /** 从持久化恢复：未绑定租约（租约与票据不跨重启，reliable-personal-entry D4）。 */
    InstanceSession(String id, String tokenHash, String instanceId, int port, String parentSessionId, Instant createdAt,
            Instant lastSeen) {
        this(id, tokenHash, instanceId, port, parentSessionId, UNBOUND, createdAt, lastSeen);
    }

    private InstanceSession(String id, String tokenHash, String instanceId, int port, String parentSessionId,
            Binding binding, Instant createdAt, Instant lastSeen) {
        this.id = id;
        this.tokenHash = tokenHash;
        this.instanceId = instanceId;
        this.port = port;
        this.parentSessionId = parentSessionId;
        this.binding = binding;
        this.createdAt = createdAt;
        this.lastSeen = lastSeen;
        this.lastPersisted = lastSeen;
    }

    public String id() {
        return id;
    }

    String tokenHash() {
        return tokenHash;
    }

    public String instanceId() {
        return instanceId;
    }

    public int port() {
        return port;
    }

    public String parentSessionId() {
        return parentSessionId;
    }

    /** 当前绑定的租约；未绑定时为 null。 */
    public String leaseId() {
        return binding.leaseId();
    }

    public long fencingToken() {
        return binding.fencingToken();
    }

    public long generation() {
        return binding.generation();
    }

    public boolean bound() {
        return binding != UNBOUND;
    }

    /** 绑定到新租约（重连 / 重启 / 租约到期后由 {@code InstanceAccess} 调用）。 */
    synchronized void rebind(String leaseId, long fencingToken, long generation) {
        binding = new Binding(leaseId, fencingToken, generation);
    }

    /** 解除与某个已撤销租约的绑定；会话本身仍有效。 */
    synchronized boolean unbindIf(String leaseId) {
        if (leaseId != null && leaseId.equals(binding.leaseId())) {
            binding = UNBOUND;
            return true;
        }
        return false;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant lastSeen() {
        return lastSeen;
    }

    void touch(Instant now) {
        lastSeen = now;
    }

    Instant lastPersisted() {
        return lastPersisted;
    }

    void persisted(Instant at) {
        lastPersisted = at;
    }

    public boolean revoked() {
        return revoked;
    }

    void revoke() {
        revoked = true;
    }
}
