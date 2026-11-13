package io.github.dipinllx.macminiappbridge.instances;

import io.github.dipinllx.macminiappbridge.audit.AuditLog;
import io.github.dipinllx.macminiappbridge.config.MmabProperties;
import io.github.dipinllx.macminiappbridge.protocol.StrictJson;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * 打开实例前的本机检查与恢复（例如 Codex 自动升级后：新版页面资源未提取、平台专用实例被关、平台仍按旧版本运行）。
 *
 * <p>只运行操作者在 {@code mmab.instances[].preflight} 里配置的固定命令，不接受浏览器传入任何参数。检查命令同步运行
 * （有超时、输出上限），stdout 为 JSON；恢复命令在后台运行，输出写入 {@code data-dir/preflight/<实例>-recover.log}。
 * 恢复命令可以重启平台本身：它的输出不经过本进程的管道，平台退出不会影响它。
 */
@Component
public class InstancePreflight {

    private static final Logger log = LoggerFactory.getLogger(InstancePreflight.class);
    static final int MAX_CHECK_OUTPUT = 64 * 1024;
    private static final int LOG_TAIL_LINES = 30;
    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]{0,40}$");

    private record Job(Process process, Instant startedAt) {
    }

    private final MmabProperties properties;
    private final AuditLog audit;
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final Map<String, Integer> exitCodes = new ConcurrentHashMap<>();

    public InstancePreflight(MmabProperties properties, AuditLog audit) {
        this.properties = properties;
        this.audit = audit;
    }

    /** @return 检查结果：{configured, ok, installedVersion, expectedVersion, problems[{code,message}]} */
    public Map<String, Object> check(MmabProperties.Instance instance) {
        Map<String, Object> m = new LinkedHashMap<>();
        MmabProperties.Preflight p = instance.preflight();
        m.put("configured", p != null);
        if (p == null) {
            m.put("ok", true);
            m.put("problems", List.of());
            return m;
        }
        m.put("recovering", recovering(instance.id()));
        try {
            Process proc = builder(p.check()).redirectErrorStream(false).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            proc.getOutputStream().close();
            java.util.concurrent.CompletableFuture<byte[]> reader = new java.util.concurrent.CompletableFuture<>();
            Thread.ofVirtual().name("mmab-preflight-" + instance.id()).start(() -> {
                try {
                    reader.complete(readBounded(proc.getInputStream()));
                } catch (IOException e) {
                    reader.completeExceptionally(e);
                }
            });
            if (!proc.waitFor(p.timeout().toMillis(), TimeUnit.MILLISECONDS)) {
                proc.destroyForcibly();
                return failed(m, "CHECK_TIMEOUT", "检查超时");
            }
            byte[] out;
            try {
                out = reader.get(2, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
                return failed(m, "CHECK_FAILED", "检查输出无效");
            }
            JsonNode n = StrictJson.WIRE.parse(new String(out, StandardCharsets.UTF_8).trim());
            m.put("ok", n.path("ok").asBoolean(false));
            m.put("installedVersion", text(n.path("installedVersion")));
            m.put("expectedVersion", text(n.path("expectedVersion")));
            List<Map<String, String>> problems = new ArrayList<>();
            for (JsonNode e : n.path("problems")) {
                String code = text(e.path("code"));
                if (code == null || !CODE.matcher(code).matches() || problems.size() >= 10) {
                    continue;
                }
                String msg = text(e.path("message"));
                problems.add(Map.of("code", code, "message", msg == null ? code : truncate(msg, 300)));
            }
            m.put("problems", problems);
            if (!Boolean.TRUE.equals(m.get("ok")) && problems.isEmpty()) {
                problems.add(Map.of("code", "CHECK_FAILED", "message", "检查未通过（退出码 " + proc.exitValue() + "）"));
            }
            return m;
        } catch (IOException | RuntimeException e) {
            log.warn("preflight check failed instance={} cause={}", instance.id(), e.getClass().getSimpleName());
            return failed(m, "CHECK_FAILED", "检查命令无法执行或输出无效");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failed(m, "CHECK_FAILED", "检查被中断");
        }
    }

    /** 启动恢复；已有恢复在运行时直接返回其状态。 */
    public synchronized Map<String, Object> recover(MmabProperties.Instance instance, String actor) {
        MmabProperties.Preflight p = instance.preflight();
        if (p == null) {
            throw new IllegalStateException("preflight not configured");
        }
        if (!recovering(instance.id())) {
            Path logFile = logFile(instance.id());
            try {
                Files.createDirectories(logFile.getParent());
                Files.writeString(logFile, "");
                Process proc = builder(p.recover())
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()))
                        .redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")))
                        .start();
                jobs.put(instance.id(), new Job(proc, Instant.now()));
                exitCodes.remove(instance.id());
                audit.record(actor, "instance.recover", instance.id(), "STARTED", null);
                proc.onExit().thenAccept(done -> {
                    exitCodes.put(instance.id(), done.exitValue());
                    jobs.remove(instance.id());
                    audit.record(actor, "instance.recover", instance.id(), done.exitValue() == 0 ? "OK" : "FAILED",
                            "exit=" + done.exitValue());
                });
            } catch (IOException e) {
                audit.record(actor, "instance.recover", instance.id(), "FAILED", "cause=start");
                throw new IllegalStateException("recover command could not start", e);
            }
        }
        return status(instance);
    }

    /** 恢复状态。平台被恢复命令重启后，新进程里没有在跑的任务，但日志尾部仍可读。 */
    public Map<String, Object> status(MmabProperties.Instance instance) {
        Map<String, Object> m = new LinkedHashMap<>();
        Job j = jobs.get(instance.id());
        m.put("running", j != null && j.process().isAlive());
        m.put("startedAt", j == null ? null : j.startedAt().toString());
        m.put("exitCode", exitCodes.get(instance.id()));
        m.put("logTail", tail(logFile(instance.id())));
        return m;
    }

    private boolean recovering(String id) {
        Job j = jobs.get(id);
        return j != null && j.process().isAlive();
    }

    private ProcessBuilder builder(List<String> command) {
        ProcessBuilder pb = new ProcessBuilder(command);
        if (properties.home() != null) {
            pb.directory(Path.of(properties.home()).toFile());
        }
        pb.environment().keySet().removeIf(k -> k.startsWith("MMAB_OPERATOR"));
        return pb;
    }

    private Path logFile(String id) {
        return Path.of(properties.dataDir()).resolve("preflight").resolve(id + "-recover.log");
    }

    private static List<String> tail(Path f) {
        try {
            if (!Files.isRegularFile(f)) {
                return List.of();
            }
            List<String> all = Files.readAllLines(f, StandardCharsets.UTF_8);
            List<String> last = all.subList(Math.max(0, all.size() - LOG_TAIL_LINES), all.size());
            return last.stream().map(l -> truncate(l, 300)).toList();
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    private static byte[] readBounded(InputStream in) throws IOException {
        byte[] buf = in.readNBytes(MAX_CHECK_OUTPUT + 1);
        if (buf.length > MAX_CHECK_OUTPUT) {
            throw new IOException("check output too large");
        }
        in.transferTo(java.io.OutputStream.nullOutputStream());
        return buf;
    }

    private static Map<String, Object> failed(Map<String, Object> m, String code, String message) {
        m.put("ok", false);
        m.put("problems", List.of(Map.of("code", code, "message", message)));
        return m;
    }

    private static String text(JsonNode n) {
        return n == null || n.isMissingNode() || n.isNull() ? null : truncate(n.asString(), 120);
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
