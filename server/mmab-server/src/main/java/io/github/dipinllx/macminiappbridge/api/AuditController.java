package io.github.dipinllx.macminiappbridge.api;

import io.github.dipinllx.macminiappbridge.audit.AuditLog;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

/** 审计元数据查询（控制台会话、同源）：持久化记录，新的在前，按序号翻页。 */
@RestController
@RequestMapping("/__mmab/api/v1")
public class AuditController {

    private final AuditLog audit;

    public AuditController(AuditLog audit) {
        this.audit = audit;
    }

    @GetMapping("/audit")
    public Map<String, Object> query(ServerWebExchange ex,
            @RequestParam(name = "limit", defaultValue = "100") int limit,
            @RequestParam(name = "before", required = false) Long before,
            @RequestParam(name = "instance", required = false) String instance,
            @RequestParam(name = "action", required = false) String action) {
        Requests.consoleSession(ex);
        String inst = instance != null && instance.matches("[a-z0-9-]{1,32}") ? instance : null;
        String act = action != null && action.matches("[a-z.]{1,40}") ? action : null;
        List<AuditLog.Row> rows = audit.query(limit, before, inst, act);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("events", rows);
        m.put("nextBefore", rows.isEmpty() ? null : rows.get(rows.size() - 1).seq());
        m.put("dropped", audit.dropped());
        return m;
    }
}
