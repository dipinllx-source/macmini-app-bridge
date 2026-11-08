package io.github.dipinllx.macminiappbridge.audit;

import io.github.dipinllx.macminiappbridge.config.MmabClock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 审计（设计 §6.3）：只记内部 ID、实例、动作、结果码、generation、原因类别。
 * 不记正文、Cookie、票据、口令、带参数 URL 或异常正文。M1 只保留内存环形缓冲 + 日志行。
 */
@Component
public class AuditLog {

    /**
     * @param at       时间
     * @param actor    内部会话 ID（不是凭据）或 {@code -}
     * @param action   动作，例如 {@code ticket.exchange}
     * @param instance 实例 ID 或 {@code -}
     * @param result   {@code OK} 或错误码
     * @param detail   原因类别（固定枚举文本），可为空
     */
    public record Event(Instant at, String actor, String action, String instance, String result, String detail) {
    }

    private static final Logger log = LoggerFactory.getLogger(AuditLog.class);
    private static final int CAPACITY = 1000;

    private final Deque<Event> events = new ArrayDeque<>();
    private final MmabClock clock;

    public AuditLog(MmabClock clock) {
        this.clock = clock;
    }

    public void record(String actor, String action, String instance, String result, String detail) {
        Event e = new Event(clock.instant(), dash(actor), action, dash(instance), result, detail == null ? "" : detail);
        synchronized (events) {
            if (events.size() >= CAPACITY) {
                events.pollFirst();
            }
            events.addLast(e);
        }
        log.info("audit action={} instance={} actor={} result={} detail={}", e.action(), e.instance(), e.actor(),
                e.result(), e.detail());
    }

    public List<Event> recent(int limit) {
        synchronized (events) {
            List<Event> all = List.copyOf(events);
            return all.subList(Math.max(0, all.size() - limit), all.size());
        }
    }

    private static String dash(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }
}
