package io.github.dipinllx.macminiappbridge.persistence;

import io.github.dipinllx.macminiappbridge.config.MmabProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

/**
 * 本机 SQLite（reliable-personal-entry D4）：{@code <mmab.data-dir>/mmab.db}，WAL、外键、忙等待。
 *
 * <p>只保存会话的令牌散列、审计元数据与实例状态；不保存租约、票据、明文令牌、口令或任何负载。
 * 版本化迁移：{@code schema_version} 记录已执行的版本号，按顺序只执行一次。
 */
@Component
public class Database {

    private static final Logger log = LoggerFactory.getLogger(Database.class);

    /** 迁移脚本：只追加，不修改已发布的条目。 */
    public static final List<String> MIGRATIONS = List.of(
            // 1: 会话（只存散列）、审计、实例状态
            """
            CREATE TABLE console_session (
              id TEXT PRIMARY KEY,
              token_hash TEXT NOT NULL UNIQUE,
              origin TEXT NOT NULL,
              created_at INTEGER NOT NULL,
              last_seen INTEGER NOT NULL,
              revoked INTEGER NOT NULL DEFAULT 0
            );
            CREATE TABLE instance_session (
              id TEXT PRIMARY KEY,
              token_hash TEXT NOT NULL UNIQUE,
              parent_id TEXT NOT NULL REFERENCES console_session(id) ON DELETE CASCADE,
              instance_id TEXT NOT NULL,
              port INTEGER NOT NULL,
              created_at INTEGER NOT NULL,
              last_seen INTEGER NOT NULL,
              revoked INTEGER NOT NULL DEFAULT 0
            );
            CREATE INDEX instance_session_parent ON instance_session(parent_id);
            CREATE TABLE audit (
              seq INTEGER PRIMARY KEY AUTOINCREMENT,
              at INTEGER NOT NULL,
              actor TEXT NOT NULL,
              action TEXT NOT NULL,
              instance TEXT NOT NULL,
              result TEXT NOT NULL,
              detail TEXT NOT NULL
            );
            CREATE INDEX audit_at ON audit(at);
            CREATE TABLE instance_state (
              instance_id TEXT PRIMARY KEY,
              desired TEXT NOT NULL,
              actual TEXT NOT NULL,
              reason TEXT,
              app_version TEXT,
              updated_at INTEGER NOT NULL
            );
            """);

    private final Path file;
    private final JdbcTemplate jdbc;

    @org.springframework.beans.factory.annotation.Autowired
    public Database(MmabProperties properties) {
        this(Path.of(properties.dataDir()).resolve("mmab.db"));
    }

    /** 测试 / 工具用：指定数据库文件。 */
    public Database(Path file) {
        this.file = file.toAbsolutePath().normalize();
        try {
            Path dir = this.file.getParent();
            Files.createDirectories(dir);
            try {
                Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
            } catch (UnsupportedOperationException ignored) {
                // non-POSIX file system
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create mmab data directory", e);
        }
        SQLiteConfig cfg = new SQLiteConfig();
        cfg.setJournalMode(SQLiteConfig.JournalMode.WAL);
        cfg.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
        cfg.enforceForeignKeys(true);
        cfg.setBusyTimeout(5000);
        SQLiteDataSource ds = new SQLiteDataSource(cfg);
        ds.setUrl("jdbc:sqlite:" + this.file);
        this.jdbc = new JdbcTemplate(ds);
        migrate();
        try {
            Files.setPosixFilePermissions(this.file, PosixFilePermissions.fromString("rw-------"));
        } catch (IOException | UnsupportedOperationException ignored) {
            // best effort
        }
    }

    public JdbcTemplate jdbc() {
        return jdbc;
    }

    public Path file() {
        return file;
    }

    public int schemaVersion() {
        return jdbc.queryForObject("SELECT COALESCE(MAX(version), 0) FROM schema_version", Integer.class);
    }

    private void migrate() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER PRIMARY KEY, applied_at INTEGER NOT NULL)");
        int current = schemaVersion();
        for (int v = current + 1; v <= MIGRATIONS.size(); v++) {
            for (String stmt : MIGRATIONS.get(v - 1).split(";")) {
                if (!stmt.isBlank()) {
                    jdbc.execute(stmt);
                }
            }
            jdbc.update("INSERT INTO schema_version(version, applied_at) VALUES (?, ?)", v, System.currentTimeMillis());
            log.info("mmab database migrated to version {}", v);
        }
    }
}
