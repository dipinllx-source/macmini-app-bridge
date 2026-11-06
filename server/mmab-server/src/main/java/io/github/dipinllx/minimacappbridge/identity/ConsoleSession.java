package io.github.dipinllx.minimacappbridge.identity;

import java.time.Instant;

/**
 * 控制台（管理 origin）会话。服务端只存 token 的 hash；CSRF token 随会话生成（同步器令牌模式）。
 */
public final class ConsoleSession {

    private final String id;
    private final String tokenHash;
    private final String csrfToken;
    private final String origin;
    private final Instant createdAt;
    private volatile Instant lastSeen;
    private volatile boolean revoked;

    ConsoleSession(String id, String tokenHash, String csrfToken, String origin, Instant createdAt) {
        this.id = id;
        this.tokenHash = tokenHash;
        this.csrfToken = csrfToken;
        this.origin = origin;
        this.createdAt = createdAt;
        this.lastSeen = createdAt;
    }

    /** 内部 ID（审计 / 租约持有者），不是凭据。 */
    public String id() {
        return id;
    }

    String tokenHash() {
        return tokenHash;
    }

    public String csrfToken() {
        return csrfToken;
    }

    /** 登录时的精确控制台 Origin。 */
    public String origin() {
        return origin;
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
