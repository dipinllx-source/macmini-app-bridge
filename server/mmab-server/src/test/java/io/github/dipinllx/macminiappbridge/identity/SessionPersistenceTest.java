package io.github.dipinllx.macminiappbridge.identity;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dipinllx.macminiappbridge.TestSupport;
import io.github.dipinllx.macminiappbridge.config.MmabClock;
import io.github.dipinllx.macminiappbridge.config.MmabProperties;
import io.github.dipinllx.macminiappbridge.persistence.Database;
import io.github.dipinllx.macminiappbridge.protocol.ErrorCode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** reliable-personal-entry 2.1–2.3 / 2.6：会话持久化、期限、父会话续期、全部撤销。 */
class SessionPersistenceTest {

    @TempDir
    Path tmp;

    private static MmabProperties props(Duration maxAge, Duration idle) {
        return new MmabProperties(null, null, List.of(), null,
                new MmabProperties.Timeouts(null, null, maxAge, idle, null, null, null), null, null,
                new MmabProperties.Testing(false, true), null);
    }

    private SessionService service(Database db, MmabClock clock, MmabProperties p) {
        return new SessionService(clock, p, new TestSupport.RecordingPublisher(), new SessionStore.Jdbc(db));
    }

    @Test
    void migrationsAreIdempotentAndTheDatabaseIsPrivate() throws Exception {
        Path file = tmp.resolve("d/mmab.db");
        Database a = new Database(file);
        assertThat(a.schemaVersion()).isEqualTo(Database.MIGRATIONS.size());
        Database b = new Database(file);
        assertThat(b.schemaVersion()).isEqualTo(Database.MIGRATIONS.size());
        assertThat(java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(file.getParent())))
                .isEqualTo("rwx------");
        assertThat(java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
                .isEqualTo("rw-------");
    }

    @Test
    void sessionsSurviveARestartAndOnlyHashesAreStored() throws Exception {
        Path file = tmp.resolve("mmab.db");
        MmabProperties p = props(null, null);
        MmabClock clock = new MmabClock(p);
        SessionService first = service(new Database(file), clock, p);
        var console = first.createConsole("https://127.0.0.1:8443");
        var inst = first.createInstance(console.session(), "qwen-01", 8444, "l-1", 7, 1);

        // "restart": a new service over the same database
        Database db = new Database(file);
        SessionService second = service(db, clock, p);
        var c2 = second.resolveConsole(console.token());
        assertThat(c2).isPresent();
        assertThat(c2.get().csrfToken()).as("CSRF is regenerated, not stored").isNotEqualTo(console.session().csrfToken());
        var i2 = second.resolveInstance(inst.token(), "qwen-01", 8444);
        assertThat(i2).isPresent();
        assertThat(i2.get().bound()).as("leases do not survive a restart").isFalse();
        assertThat(i2.get().leaseId()).isNull();

        String dump = String.join("\n", db.jdbc().queryForList("SELECT * FROM console_session").toString(),
                db.jdbc().queryForList("SELECT * FROM instance_session").toString());
        assertThat(dump).doesNotContain(console.token()).doesNotContain(inst.token())
                .doesNotContain(console.session().csrfToken());
    }

    @Test
    void defaultLifetimeIsSevenDaysAndUseWithinItKeepsTheSession() {
        MmabProperties p = props(null, null);
        MmabClock clock = new MmabClock(p);
        SessionService s = service(new Database(tmp.resolve("mmab.db")), clock, p);
        var c = s.createConsole("https://127.0.0.1:8443");
        clock.advance(Duration.ofDays(3));
        assertThat(s.resolveConsole(c.token())).as("3 idle days: still valid").isPresent();
        clock.advance(Duration.ofDays(3).plusHours(23));
        assertThat(s.resolveConsole(c.token())).isPresent();
        clock.advance(Duration.ofHours(2));
        assertThat(s.resolveConsole(c.token())).as("past 7 days").isEmpty();
    }

    @Test
    void instanceActivityKeepsTheParentConsoleSessionAlive() {
        MmabProperties p = props(Duration.ofDays(7), Duration.ofMinutes(30));
        MmabClock clock = new MmabClock(p);
        SessionService s = service(new Database(tmp.resolve("mmab.db")), clock, p);
        var c = s.createConsole("https://127.0.0.1:8443");
        var i = s.createInstance(c.session(), "codex-01", 8445, "l-1", 1, 1);
        for (int k = 0; k < 4; k++) { // 4 x 20 min, only the instance tab is used
            clock.advance(Duration.ofMinutes(20));
            assertThat(s.resolveInstance(i.token(), "codex-01", 8445)).isPresent();
        }
        assertThat(s.resolveConsole(c.token())).as("parent renewed by instance activity").isPresent();

        // heartbeat-only activity (data connection) also renews both
        for (int k = 0; k < 4; k++) {
            clock.advance(Duration.ofMinutes(20));
            s.touchActive(i.session());
        }
        assertThat(s.resolveInstance(i.token(), "codex-01", 8445)).isPresent();
        assertThat(s.resolveConsole(c.token())).isPresent();
    }

    @Test
    void revokeAllEndsEverySessionIncludingRestoredOnes() {
        Path file = tmp.resolve("mmab.db");
        MmabProperties p = props(null, null);
        MmabClock clock = new MmabClock(p);
        SessionService s = service(new Database(file), clock, p);
        var a = s.createConsole("https://127.0.0.1:8443");
        var b = s.createConsole("https://127.0.0.1:8443");
        var i = s.createInstance(a.session(), "qwen-01", 8444, "l-1", 1, 1);
        assertThat(s.revokeAll(ErrorCode.UNAUTHENTICATED)).hasSize(2);
        assertThat(s.resolveConsole(a.token())).isEmpty();
        assertThat(s.resolveConsole(b.token())).isEmpty();
        assertThat(s.resolveInstance(i.token(), "qwen-01", 8444)).isEmpty();
        SessionService restarted = service(new Database(file), clock, p);
        assertThat(restarted.resolveConsole(a.token())).as("revocation is persisted").isEmpty();
        assertThat(restarted.activeConsoleSessions()).isZero();
    }
}
