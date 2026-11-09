package io.github.dipinllx.macminiappbridge.audit;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dipinllx.macminiappbridge.TestSupport;
import io.github.dipinllx.macminiappbridge.config.MmabClock;
import io.github.dipinllx.macminiappbridge.persistence.Database;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/** reliable-personal-entry 任务组 4：审计持久化、保留期、detail 白名单。 */
class AuditLogTest {

    @TempDir
    Path tmp;

    private static AuditLog log(Database db, MmabClock clock, Duration retention) {
        StaticListableBeanFactory f = new StaticListableBeanFactory();
        if (db != null) {
            f.addBean("db", db);
        }
        return new AuditLog(clock, f.getBeanProvider(Database.class), retention);
    }

    @Test
    void recordsSurviveARestartAndPageNewestFirst() {
        Path file = tmp.resolve("mmab.db");
        MmabClock clock = TestSupport.clock();
        AuditLog a = log(new Database(file), clock, null);
        for (int i = 0; i < 5; i++) {
            a.record("cs-1", "transport.open", "qwen-01", "OK", "connection=c-" + i);
        }
        a.record("cs-1", "auth.login", null, "OK", null);
        a.close();

        AuditLog b = log(new Database(file), clock, null); // "restart"
        var page1 = b.query(3, null, null, null);
        assertThat(page1).extracting(AuditLog.Row::action).containsExactly("auth.login", "transport.open", "transport.open");
        var page2 = b.query(3, page1.get(2).seq(), null, null);
        assertThat(page2).extracting(AuditLog.Row::detail).containsExactly("connection=c-2", "connection=c-1", "connection=c-0");
        assertThat(b.query(10, null, "qwen-01", "transport.")).hasSize(5);
        assertThat(b.query(10, null, null, "auth.")).hasSize(1).first()
                .satisfies(r -> assertThat(r.instance()).isEqualTo("-"));
        b.close();
    }

    @Test
    void retentionPurgesOldRecordsAtMostHourly() {
        MmabClock clock = TestSupport.clock();
        AuditLog a = log(new Database(tmp.resolve("mmab.db")), clock, Duration.ofDays(30));
        a.record("cs-1", "auth.login", null, "OK", null);
        a.flush();
        clock.advance(Duration.ofDays(31));
        a.record("cs-1", "auth.logout", null, "OK", null);
        a.flush();
        assertThat(a.purgeExpired()).isEqualTo(1);
        assertThat(a.query(10, null, null, null)).extracting(AuditLog.Row::action).containsExactly("auth.logout");
        assertThat(a.purgeExpired()).as("throttled").isZero();
        a.close();
    }

    @Test
    void detailWhitelistKeepsDiagnosticsAndRejectsContent() {
        AuditLog a = log(null, TestSupport.clock(), null);
        assertThat(a.sanitize("connection=c-abc cause=OVERFLOW lane=CONTROL frameBytes=131")).contains("lane=CONTROL");
        assertThat(a.sanitize("e=close code=1006 recent=view:log-message,host:thread-updated prev.up=5"))
                .contains("recent=view:log-message,host:thread-updated");
        assertThat(a.sanitize("UNKNOWN_OR_REPLAYED")).isEqualTo("UNKNOWN_OR_REPLAYED");
        assertThat(a.sanitize(null)).isEmpty();
        // free text, quotes, URLs with query strings, JSON: never stored
        assertThat(a.sanitize("message=hello world, secret!")).isEqualTo("detail=REJECTED");
        assertThat(a.sanitize("url=https://x/y?token=abc")).isEqualTo("detail=REJECTED");
        assertThat(a.sanitize("{\"password\":\"x\"}")).isEqualTo("detail=REJECTED");
        assertThat(a.rejectedDetails()).isEqualTo(3);
        assertThat(a.sanitize(("k=v ").repeat(1000))).endsWith("truncated=true").hasSizeLessThan(AuditLog.MAX_DETAIL + 20);
        // identifiers are constrained too
        a.record("cs-1 x", "auth login", "../etc", "OK!", null);
        AuditLog.Event e = a.recent(1).get(0);
        assertThat(e.actor()).isEqualTo("INVALID");
        assertThat(e.action()).isEqualTo("INVALID");
        assertThat(e.instance()).isEqualTo("INVALID");
        assertThat(e.result()).isEqualTo("INVALID");
    }

    @Test
    void aFullQueueDropsInsteadOfBlocking() {
        AuditLog a = log(new Database(tmp.resolve("mmab.db")), TestSupport.clock(), null);
        long t0 = System.nanoTime();
        for (int i = 0; i < AuditLog.QUEUE_CAPACITY * 3; i++) {
            a.record("cs-1", "bridge.request", "codex-01", "OK", "n=" + i);
        }
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(5));
        a.flush();
        long stored = a.query(500, null, null, null).size();
        assertThat(stored).isPositive();
        a.close();
    }
}
