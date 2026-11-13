package io.github.dipinllx.macminiappbridge.instances;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dipinllx.macminiappbridge.TestSupport;
import io.github.dipinllx.macminiappbridge.audit.AuditLog;
import io.github.dipinllx.macminiappbridge.config.MmabProperties;
import io.github.dipinllx.macminiappbridge.persistence.Database;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/** 打开前检查与恢复：固定命令、JSON 结果、超时、后台恢复与日志尾部。 */
class InstancePreflightTest {

    @TempDir
    Path tmp;

    private InstancePreflight preflight() {
        MmabProperties props = new MmabProperties(null, null, List.of(), null, null, null, null,
                new MmabProperties.Testing(false, true), tmp.toString(), tmp.resolve("data").toString());
        AuditLog audit = new AuditLog(TestSupport.clock(), new StaticListableBeanFactory().getBeanProvider(Database.class),
                Duration.ofDays(30));
        return new InstancePreflight(props, audit);
    }

    private static MmabProperties.Instance instance(MmabProperties.Preflight p) {
        return new MmabProperties.Instance("codex-01", "codex", "Codex", 8445, "electron-renderer-cdp", true, null, p);
    }

    private static List<String> sh(String script) {
        return List.of("/bin/sh", "-c", script);
    }

    @Test
    void notConfiguredIsOk() {
        Map<String, Object> r = preflight().check(instance(null));
        assertThat(r).containsEntry("configured", false).containsEntry("ok", true);
    }

    @Test
    void reportsTheCheckScriptsVerdict() {
        String json = "{\"ok\":false,\"installedVersion\":\"26.2\",\"expectedVersion\":\"26.1\",\"problems\":["
                + "{\"code\":\"VERSION_CHANGED\",\"message\":\"已升级\"},{\"code\":\"bad code\",\"message\":\"x\"}]}";
        Map<String, Object> r = preflight().check(instance(new MmabProperties.Preflight(
                sh("printf '%s' '" + json + "'"), sh("true"), null)));
        assertThat(r).containsEntry("configured", true).containsEntry("ok", false)
                .containsEntry("installedVersion", "26.2").containsEntry("expectedVersion", "26.1");
        assertThat(r.get("problems")).asList().hasSize(1).first().asString().contains("VERSION_CHANGED");

        Map<String, Object> ok = preflight().check(instance(new MmabProperties.Preflight(
                sh("echo '{\"ok\":true,\"problems\":[]}'"), sh("true"), null)));
        assertThat(ok).containsEntry("ok", true);
    }

    @Test
    void invalidOutputAndTimeoutAreFailures() {
        Map<String, Object> bad = preflight().check(instance(new MmabProperties.Preflight(sh("echo not-json"), sh("true"), null)));
        assertThat(bad).containsEntry("ok", false);
        assertThat(bad.get("problems").toString()).contains("CHECK_FAILED");

        Map<String, Object> slow = preflight().check(instance(new MmabProperties.Preflight(
                sh("sleep 5"), sh("true"), Duration.ofMillis(300))));
        assertThat(slow.get("problems").toString()).contains("CHECK_TIMEOUT");
    }

    @Test
    void recoverRunsInTheBackgroundOnceAndKeepsALogTail() throws Exception {
        InstancePreflight p = preflight();
        Path marker = tmp.resolve("ran");
        MmabProperties.Instance i = instance(new MmabProperties.Preflight(sh("echo '{\"ok\":true}'"),
                sh("echo step1; sleep 1; echo step2; echo x >> '" + marker + "'"), null));
        Map<String, Object> started = p.recover(i, "cs-test");
        assertThat(started).containsEntry("running", true);
        p.recover(i, "cs-test"); // a second click while running does not start another run
        for (int k = 0; k < 50 && p.status(i).get("exitCode") == null; k++) {
            Thread.sleep(100);
        }
        Map<String, Object> done = p.status(i);
        assertThat(done).containsEntry("running", false).containsEntry("exitCode", 0);
        assertThat(done.get("logTail")).asList().containsExactly("step1", "step2");
        assertThat(Files.readAllLines(marker)).hasSize(1);
    }

    @Test
    void recoverNeedsConfiguration() {
        assertThatThrownBy(() -> preflight().recover(instance(null), "cs")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new MmabProperties.Preflight(List.of(), List.of("x"), null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
