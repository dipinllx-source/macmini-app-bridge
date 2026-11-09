package io.github.dipinllx.macminiappbridge.identity;

import io.github.dipinllx.macminiappbridge.persistence.Database;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 会话持久化（reliable-personal-entry D4）。只保存令牌散列与时间戳；不保存明文令牌、CSRF token、租约或票据。
 */
public interface SessionStore {

    record ConsoleRow(String id, String tokenHash, String origin, Instant createdAt, Instant lastSeen) {
    }

    record InstanceRow(String id, String tokenHash, String parentId, String instanceId, int port, Instant createdAt,
            Instant lastSeen) {
    }

    void saveConsole(ConsoleSession s);

    void saveInstance(InstanceSession s);

    void touch(String id, boolean console, Instant at);

    void revoke(String id, boolean console);

    /** 撤销全部会话。 */
    void revokeAll();

    List<ConsoleRow> activeConsoles();

    List<InstanceRow> activeInstances();

    /** 删除已撤销或已过期（创建早于 createdBefore / 最近使用早于 lastSeenBefore）的会话。 */
    int purge(Instant createdBefore, Instant lastSeenBefore);

    /** 不持久化（单元测试）。 */
    SessionStore NONE = new SessionStore() {
        @Override public void saveConsole(ConsoleSession s) { }
        @Override public void saveInstance(InstanceSession s) { }
        @Override public void touch(String id, boolean console, Instant at) { }
        @Override public void revoke(String id, boolean console) { }
        @Override public void revokeAll() { }
        @Override public List<ConsoleRow> activeConsoles() { return List.of(); }
        @Override public List<InstanceRow> activeInstances() { return List.of(); }
        @Override public int purge(Instant createdBefore, Instant lastSeenBefore) { return 0; }
    };

    /** SQLite 实现。 */
    final class Jdbc implements SessionStore {
        private final JdbcTemplate jdbc;

        public Jdbc(Database db) {
            this.jdbc = db.jdbc();
        }

        @Override
        public void saveConsole(ConsoleSession s) {
            jdbc.update("INSERT INTO console_session(id, token_hash, origin, created_at, last_seen) VALUES (?,?,?,?,?)",
                    s.id(), s.tokenHash(), s.origin(), s.createdAt().toEpochMilli(), s.lastSeen().toEpochMilli());
        }

        @Override
        public void saveInstance(InstanceSession s) {
            jdbc.update("INSERT INTO instance_session(id, token_hash, parent_id, instance_id, port, created_at, last_seen)"
                    + " VALUES (?,?,?,?,?,?,?)", s.id(), s.tokenHash(), s.parentSessionId(), s.instanceId(), s.port(),
                    s.createdAt().toEpochMilli(), s.lastSeen().toEpochMilli());
        }

        @Override
        public void touch(String id, boolean console, Instant at) {
            jdbc.update("UPDATE " + table(console) + " SET last_seen = ? WHERE id = ? AND last_seen < ?",
                    at.toEpochMilli(), id, at.toEpochMilli());
        }

        @Override
        public void revoke(String id, boolean console) {
            jdbc.update("UPDATE " + table(console) + " SET revoked = 1 WHERE id = ?", id);
            if (console) {
                jdbc.update("UPDATE instance_session SET revoked = 1 WHERE parent_id = ?", id);
            }
        }

        @Override
        public void revokeAll() {
            jdbc.update("UPDATE instance_session SET revoked = 1");
            jdbc.update("UPDATE console_session SET revoked = 1");
        }

        @Override
        public List<ConsoleRow> activeConsoles() {
            return jdbc.query("SELECT id, token_hash, origin, created_at, last_seen FROM console_session WHERE revoked = 0",
                    (rs, n) -> new ConsoleRow(rs.getString(1), rs.getString(2), rs.getString(3),
                            Instant.ofEpochMilli(rs.getLong(4)), Instant.ofEpochMilli(rs.getLong(5))));
        }

        @Override
        public List<InstanceRow> activeInstances() {
            return jdbc.query("SELECT id, token_hash, parent_id, instance_id, port, created_at, last_seen"
                    + " FROM instance_session WHERE revoked = 0",
                    (rs, n) -> new InstanceRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getInt(5), Instant.ofEpochMilli(rs.getLong(6)), Instant.ofEpochMilli(rs.getLong(7))));
        }

        @Override
        public int purge(Instant createdBefore, Instant lastSeenBefore) {
            String cond = " WHERE revoked = 1 OR created_at < ? OR last_seen < ?";
            int i = jdbc.update("DELETE FROM instance_session" + cond, createdBefore.toEpochMilli(),
                    lastSeenBefore.toEpochMilli());
            return i + jdbc.update("DELETE FROM console_session" + cond, createdBefore.toEpochMilli(),
                    lastSeenBefore.toEpochMilli());
        }

        private static String table(boolean console) {
            return console ? "console_session" : "instance_session";
        }
    }
}
