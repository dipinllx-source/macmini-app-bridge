package io.github.dipinllx.minimacappbridge.api;

import io.github.dipinllx.minimacappbridge.audit.AuditLog;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

/** 最近的审计元数据（控制台会话、同源）。 */
@RestController
@RequestMapping("/__mmab/api/v1")
public class AuditController {

    private final AuditLog audit;

    public AuditController(AuditLog audit) {
        this.audit = audit;
    }

    @GetMapping("/audit")
    public List<AuditLog.Event> recent(ServerWebExchange ex) {
        Requests.consoleSession(ex);
        return audit.recent(200);
    }
}
