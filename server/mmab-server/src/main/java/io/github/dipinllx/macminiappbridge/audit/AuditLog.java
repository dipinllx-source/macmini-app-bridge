package io.github.dipinllx.macminiappbridge.audit;

import io.github.dipinllx.macminiappbridge.config.MmabClock;
import io.github.dipinllx.macminiappbridge.persistence.Database;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 审计（设计 §6.3；reliable-personal-entry 任务组 4）：只记内部 ID、实例、动作、结果码与固定格式的诊断元数据。
 * 不记正文、Cookie、票据、口令、带参数 URL 或异常正文。
 *
 * <ul>
 *   <li>持久化：异步写入 {@code mmab.db} 的 {@code audit} 表；有界队列（{@value #QUEUE_CAPACITY}），满时丢弃并计数，
 *       永不阻塞请求路径。重启后可在控制台查询。</li>
 *   <li>保留期：{@code mmab.audit.retention}（默认 30 天），定时清理。</li>
 *   <li>白名单（4.2）：{@code detail} 只允许空格分隔的 {@code key=value} 或单个标识符，值限定字符集，总长有上限；
 *       不合规的 detail 整体替换为 {@code detail=REJECTED}，绝不原样落库。</li>
 * </ul>
 */
@Component
public class AuditLog {

    /**
     * @param at       时间
     * @param actor    内部会话 ID（不是凭据）或 {@code -}
     * @param action   动作，例如 {@code ticket.exchange}
     * @param instance 实例 ID 或 {@code -}
     * @param result   {@code OK} 或错误码
     * @param detail   固定格式的诊断元数据，可为空
     */
    public record Event(Instant at, String actor, String action, String instance, String result, String detail) {
    }

    /** 查询结果中的一条（带序号，用于翻页）。 */
    public record Row(long seq, Instant at, String actor, String action, String instance, String result, String detail) {
    }

    private static final Logger log = LoggerFactory.getLogger(AuditLog.class);
    private static final int CAPACITY = 1000;
    static final int QUEUE_CAPACITY = 4096;
    static final int MAX_DETAIL = 2000;
    private static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,63}");
    private static final Pattern RESULT = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
    private static final Pattern TOKEN = Pattern.compile(
            "([A-Za-z][A-Za-z0-9_.]{0,39}=[A-Za-z0-9_.:,/+@-]{0,1200}|[A-Za-z0-9_.-]{1,64})");
    private static final Duration PURGE_EVERY = Duration.ofHours(1);

    private final Deque<Event> events = new ArrayDeque<>();
    private final MmabClock clock;
    private final JdbcTemplate jdbc;
    private final Duration retention;
    private final BlockingQueue<Event> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong rejectedDetails = new AtomicLong();
    private final Thread writer;
    private volatile boolean running = true;
    private volatile Instant lastPurge = Instant.EPOCH;

    public AuditLog(MmabClock clock, ObjectProvider<Database> db,
            @Value("${mmab.audit.retention:30d}") Duration retention) {
        this.clock = clock;
        Database d = db.getIfAvailable();
        this.jdbc = d == null ? null : d.jdbc();
        this.retention = retention == null || retention.isNegative() || retention.isZero() ? Duration.ofDays(30) : retention;
        this.writer = jdbc == null ? null : Thread.ofVirtual().name("mmab-audit-writer").start(this::writeLoop);
    }

    public void record(String actor, String action, String instance, String result, String detail) {
        Event e = new Event(clock.instant(), id(actor), name(action), id(instance), result(result), sanitize(detail));
        synchronized (events) {
            if (events.size() >= CAPACITY) {
                events.pollFirst();
            }
            events.addLast(e);
        }
        log.info("audit action={} instance={} actor={} result={} detail={}", e.action(), e.instance(), e.actor(),
                e.result(), e.detail());
        if (jdbc != null) {
            pending.incrementAndGet();
            if (!queue.offer(e)) {
                pending.decrementAndGet();
                dropped.incrementAndGet();
            }
        }
    }

    /** 内存中最近的事件（进程内诊断 / 测试）。 */
    public List<Event> recent(int limit) {
        synchronized (events) {
            List<Event> all = List.copyOf(events);
            return all.subList(Math.max(0, all.size() - limit), all.size());
        }
    }

    /**
     * 持久化查询（新的在前）。
     *
     * @param before   只返回序号小于它的记录（翻页）；null 为最新
     * @param instance 过滤实例；null 不过滤
     * @param action   过滤动作前缀（如 {@code transport.}）；null 不过滤
     */
    public List<Row> query(int limit, Long before, String instance, String action) {
        int n = Math.max(1, Math.min(limit, 500));
        if (jdbc == null) {
            List<Row> rows = new ArrayList<>();
            List<Event> all = recent(CAPACITY);
            for (int i = all.size() - 1; i >= 0 && rows.size() < n; i--) {
                Event e = all.get(i);
                if ((instance == null || instance.equals(e.instance())) && (action == null || e.action().startsWith(action))) {
                    rows.add(new Row(i + 1, e.at(), e.actor(), e.action(), e.instance(), e.result(), e.detail()));
                }
            }
            return rows;
        }
        flush();
        StringBuilder sql = new StringBuilder("SELECT seq, at, actor, action, instance, result, detail FROM audit WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (before != null) {
            sql.append(" AND seq < ?");
            args.add(before);
        }
        if (instance != null) {
            sql.append(" AND instance = ?");
            args.add(instance);
        }
        if (action != null) {
            sql.append(" AND action LIKE ? ESCAPE '\\'");
            args.add(action.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        sql.append(" ORDER BY seq DESC LIMIT ?");
        args.add(n);
        return jdbc.query(sql.toString(), (rs, i) -> new Row(rs.getLong(1), Instant.ofEpochMilli(rs.getLong(2)),
                rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7)), args.toArray());
    }

    public long dropped() {
        return dropped.get();
    }

    public long rejectedDetails() {
        return rejectedDetails.get();
    }

    /** 等待队列中的事件写入数据库（查询前 / 关闭前 / 测试）。 */
    public void flush() {
        if (jdbc == null) {
            return;
        }
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (pending.get() > 0 && System.nanoTime() < end) {
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** 清理超过保留期的记录（维护清扫调用，内部按小时节流）。 */
    public int purgeExpired() {
        if (jdbc == null) {
            return 0;
        }
        Instant now = clock.instant();
        if (now.isBefore(lastPurge.plus(PURGE_EVERY))) {
            return 0;
        }
        lastPurge = now;
        return jdbc.update("DELETE FROM audit WHERE at < ?", now.minus(retention).toEpochMilli());
    }

    @PreDestroy
    void close() {
        flush();
        running = false;
        if (writer != null) {
            writer.interrupt();
        }
    }

    private void writeLoop() {
        List<Event> batch = new ArrayList<>();
        while (running) {
            try {
                Event first = queue.poll(500, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                {
                    batch.add(first);
                    queue.drainTo(batch, 255);
                    jdbc.batchUpdate("INSERT INTO audit(at, actor, action, instance, result, detail) VALUES (?,?,?,?,?,?)",
                            batch, batch.size(), (ps, e) -> {
                                ps.setLong(1, e.at().toEpochMilli());
                                ps.setString(2, e.actor());
                                ps.setString(3, e.action());
                                ps.setString(4, e.instance());
                                ps.setString(5, e.result());
                                ps.setString(6, e.detail());
                            });
                    pending.addAndGet(-batch.size());
                    batch.clear();
                }
            } catch (InterruptedException e) {
                if (!running) {
                    return;
                }
            } catch (RuntimeException e) {
                dropped.addAndGet(batch.size());
                pending.addAndGet(-batch.size());
                batch.clear();
                log.warn("audit write failed: {}", e.getClass().getSimpleName());
            }
        }
    }

    /** 4.2：detail 白名单。合规则原样（截断到 {@value #MAX_DETAIL} 字符的整词边界），否则 {@code detail=REJECTED}。 */
    String sanitize(String detail) {
        if (detail == null || detail.isBlank()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (String token : detail.trim().split(" +")) {
            if (!TOKEN.matcher(token).matches()) {
                rejectedDetails.incrementAndGet();
                return "detail=REJECTED";
            }
            if (out.length() + token.length() + 1 > MAX_DETAIL) {
                out.append(" truncated=true");
                break;
            }
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(token);
        }
        return out.toString();
    }

    private static String id(String s) {
        return s == null || s.isBlank() ? "-" : NAME.matcher(s).matches() ? s : "INVALID";
    }

    private static String name(String s) {
        return s != null && NAME.matcher(s).matches() ? s : "INVALID";
    }

    private static String result(String s) {
        return s != null && RESULT.matcher(s).matches() ? s : "INVALID";
    }
}
