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
    private final String leaseId;
    private final long fencingToken;
    private final long generation;
    private final Instant createdAt;
    private volatile Instant lastSeen;
    private volatile boolean revoked;

    InstanceSession(String id, String tokenHash, String instanceId, int port, String parentSessionId, String leaseId,
            long fencingToken, long generation, Instant createdAt) {
        this.id = id;
        this.tokenHash = tokenHash;
        this.instanceId = instanceId;
        this.port = port;
        this.parentSessionId = parentSessionId;
        this.leaseId = leaseId;
        this.fencingToken = fencingToken;
        this.generation = generation;
        this.createdAt = createdAt;
        this.lastSeen = createdAt;
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

    public String leaseId() {
        return leaseId;
    }

    public long fencingToken() {
        return fencingToken;
    }

    public long generation() {
        return generation;
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

    public boolean revoked() {
        return revoked;
    }

    void revoke() {
        revoked = true;
    }
}
