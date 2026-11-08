package io.github.dipinllx.macminiappbridge.api;

import io.github.dipinllx.macminiappbridge.audit.AuditLog;
import io.github.dipinllx.macminiappbridge.identity.ConsoleSession;
import io.github.dipinllx.macminiappbridge.identity.LaunchTicketService;
import io.github.dipinllx.macminiappbridge.instances.AdapterDescriptor;
import io.github.dipinllx.macminiappbridge.instances.InstanceRegistry;
import io.github.dipinllx.macminiappbridge.instances.InstanceRuntime;
import io.github.dipinllx.macminiappbridge.leases.Lease;
import io.github.dipinllx.macminiappbridge.leases.LeaseManager;
import io.github.dipinllx.macminiappbridge.protocol.ErrorCode;
import io.github.dipinllx.macminiappbridge.web.ApiException;
import io.github.dipinllx.macminiappbridge.web.ListenerRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

/**
 * 控制台 API：实例列表、独占租约、开窗票据（设计 §10.2）。只返回元数据，不返回调试端口、
 * 适配器内部端口或任何账号信息。
 */
@RestController
@RequestMapping("/__mmab/api/v1")
public class InstancesController {

    /** @param fencingToken 续租时可选携带，用于 fence 校验 */
    public record HeartbeatRequest(Long fencingToken) {
    }

    private final InstanceRegistry instances;
    private final LeaseManager leases;
    private final LaunchTicketService tickets;
    private final ListenerRegistry listeners;
    private final AuditLog audit;

    public InstancesController(InstanceRegistry instances, LeaseManager leases, LaunchTicketService tickets,
            ListenerRegistry listeners, AuditLog audit) {
        this.instances = instances;
        this.leases = leases;
        this.tickets = tickets;
        this.listeners = listeners;
        this.audit = audit;
    }

    @GetMapping("/instances")
    public List<Map<String, Object>> list(ServerWebExchange ex) {
        ConsoleSession s = Requests.consoleSession(ex);
        return instances.all().stream().map(rt -> describe(rt, s)).toList();
    }

    @GetMapping("/instances/{id}")
    public Map<String, Object> get(@PathVariable String id, ServerWebExchange ex) {
        ConsoleSession s = Requests.consoleSession(ex);
        return describe(require(id), s);
    }

    @PostMapping("/instances/{id}/leases")
    public ResponseEntity<Map<String, Object>> acquire(@PathVariable String id, ServerWebExchange ex) {
        ConsoleSession s = Requests.consoleSession(ex);
        InstanceRuntime rt = requireOpenable(id);
        LeaseManager.AcquireResult r = leases.acquire(id, s.id(), rt.generation());
        audit.record(s.id(), "lease.acquire", id, r.ok() ? "OK" : r.rejection().name(), null);
        if (!r.ok()) {
            throw new ApiException(HttpStatus.CONFLICT, r.rejection());
        }
        return ResponseEntity.status(r.reused() ? HttpStatus.OK : HttpStatus.CREATED).body(leaseBody(r.lease()));
    }

    @PostMapping("/leases/{leaseId}/heartbeat")
    public Map<String, Object> heartbeat(@PathVariable String leaseId,
            @RequestBody(required = false) HeartbeatRequest body, ServerWebExchange ex) {
        ConsoleSession s = Requests.consoleSession(ex);
        Optional<ErrorCode> rejection = leases.heartbeat(leaseId, s.id(), body == null ? null : body.fencingToken());
        if (rejection.isPresent()) {
            audit.record(s.id(), "lease.heartbeat", null, rejection.get().name(), null);
            throw new ApiException(HttpStatus.CONFLICT, rejection.get());
        }
        return leaseBody(leases.findById(leaseId).orElseThrow(
                () -> new ApiException(HttpStatus.CONFLICT, ErrorCode.STALE_GENERATION)));
    }

    @DeleteMapping("/leases/{leaseId}")
    public ResponseEntity<Void> release(@PathVariable String leaseId, ServerWebExchange ex) {
        ConsoleSession s = Requests.consoleSession(ex);
        boolean released = leases.release(leaseId, s.id(), ErrorCode.LEASE_REQUIRED);
        audit.record(s.id(), "lease.release", null, released ? "OK" : ErrorCode.NOT_FOUND.name(), null);
        if (!released) {
            throw new ApiException(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND);
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * 签发一次性开窗票据（设计 §5.3 第 2 步）：原子获取 / 复用本会话的独占租约，目标端口与目标 Origin
     * 由服务端决定（主机取自已校验的控制台 Origin），不接受任意 redirect URL。
     */
    @PostMapping("/instances/{id}/launch-tickets")
    public ResponseEntity<Map<String, Object>> launchTicket(@PathVariable String id, ServerWebExchange ex) {
        ConsoleSession s = Requests.consoleSession(ex);
        InstanceRuntime rt = requireOpenable(id);
        int port = listeners.instancePort(id)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, ErrorCode.PORT_OWNER_MISMATCH));
        String consoleOrigin = ex.getRequest().getHeaders().getOrigin();
        String host = listeners.originHost(consoleOrigin, listeners.consolePort())
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, ErrorCode.ORIGIN_REJECTED));
        LeaseManager.AcquireResult r = leases.acquire(id, s.id(), rt.generation());
        if (!r.ok()) {
            audit.record(s.id(), "ticket.issue", id, r.rejection().name(), null);
            throw new ApiException(HttpStatus.CONFLICT, r.rejection());
        }
        Lease lease = r.lease();
        String targetOrigin = listeners.origin(host, port);
        LaunchTicketService.Issued issued = tickets.issue(id, port, targetOrigin, consoleOrigin, s.id(),
                lease.leaseId(), lease.fencingToken(), lease.generation());
        audit.record(s.id(), "ticket.issue", id, "OK", "generation=" + lease.generation());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ticket", issued.token());
        body.put("exchangeUrl", targetOrigin + "/__mmab/session/exchange");
        body.put("expiresAt", issued.ticket().expiresAt().toString());
        body.put("lease", leaseBody(lease));
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    private InstanceRuntime require(String id) {
        return instances.get(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND));
    }

    private InstanceRuntime requireOpenable(String id) {
        InstanceRuntime rt = require(id);
        if (!rt.ready()) {
            ErrorCode reason = rt.blockedReason() != null ? rt.blockedReason() : ErrorCode.ADAPTER_UNAVAILABLE;
            throw new ApiException(HttpStatus.CONFLICT, reason);
        }
        return rt;
    }

    private Map<String, Object> describe(InstanceRuntime rt, ConsoleSession s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rt.id());
        m.put("appId", rt.config().appId());
        m.put("displayName", rt.config().displayName());
        m.put("strategy", rt.config().strategy());
        m.put("enabled", rt.config().enabled());
        m.put("port", listeners.instancePort(rt.id()).orElse(rt.config().port()));
        m.put("state", rt.state().name());
        m.put("blockedReason", rt.blockedReason() == null ? null : rt.blockedReason().name());
        m.put("generation", rt.generation());
        m.put("openable", rt.ready() && listeners.instancePort(rt.id()).isPresent());
        AdapterDescriptor d = rt.descriptor();
        if (d != null) {
            m.put("profileId", d.profileId());
            m.put("risk", d.riskLevel());
            Map<String, String> caps = new LinkedHashMap<>();
            d.capabilities().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(e -> caps.put(e.getKey().id(), e.getValue().name()));
            m.put("capabilities", caps);
        }
        Map<String, Object> lease = new LinkedHashMap<>();
        Optional<Lease> current = leases.current(rt.id());
        lease.put("held", current.isPresent());
        lease.put("mine", current.map(l -> l.holderSessionId().equals(s.id())).orElse(false));
        current.filter(l -> l.holderSessionId().equals(s.id())).ifPresent(l -> {
            lease.put("leaseId", l.leaseId());
            lease.put("expiresAt", l.expiresAt().toString());
        });
        m.put("lease", lease);
        return m;
    }

    private static Map<String, Object> leaseBody(Lease l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("leaseId", l.leaseId());
        m.put("instanceId", l.instanceId());
        m.put("fencingToken", l.fencingToken());
        m.put("generation", l.generation());
        m.put("expiresAt", l.expiresAt().toString());
        return m;
    }
}
