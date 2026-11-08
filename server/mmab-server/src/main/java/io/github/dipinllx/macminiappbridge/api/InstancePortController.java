package io.github.dipinllx.macminiappbridge.api;

import io.github.dipinllx.macminiappbridge.audit.AuditLog;
import io.github.dipinllx.macminiappbridge.identity.ConsoleSession;
import io.github.dipinllx.macminiappbridge.identity.InstanceSession;
import io.github.dipinllx.macminiappbridge.identity.LaunchTicketService;
import io.github.dipinllx.macminiappbridge.identity.SessionService;
import io.github.dipinllx.macminiappbridge.instances.AdapterDescriptor;
import io.github.dipinllx.macminiappbridge.instances.InstanceRegistry;
import io.github.dipinllx.macminiappbridge.instances.InstanceRuntime;
import io.github.dipinllx.macminiappbridge.leases.LeaseManager;
import io.github.dipinllx.macminiappbridge.protocol.AdapterProtocol;
import io.github.dipinllx.macminiappbridge.protocol.ErrorCode;
import io.github.dipinllx.macminiappbridge.proxy.BridgeSocketHandler;
import io.github.dipinllx.macminiappbridge.web.ApiException;
import io.github.dipinllx.macminiappbridge.web.ListenerRegistry;
import io.github.dipinllx.macminiappbridge.web.MmabAttributes;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.socket.server.support.HandshakeWebSocketService;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 实例端口上的平台端点（设计 §5.3）：票据兑换、实例注销、引导数据、数据面 WS。
 * 访问控制由 {@code GuardFilter} 先行完成，这里只做业务绑定校验。
 */
@RestController
public class InstancePortController {

    private final LaunchTicketService tickets;
    private final SessionService sessions;
    private final LeaseManager leases;
    private final InstanceRegistry instances;
    private final ListenerRegistry listeners;
    private final BridgeSocketHandler bridge;
    private final HandshakeWebSocketService webSocketService;
    private final AuditLog audit;

    public InstancePortController(LaunchTicketService tickets, SessionService sessions, LeaseManager leases,
            InstanceRegistry instances, ListenerRegistry listeners, BridgeSocketHandler bridge,
            HandshakeWebSocketService webSocketService, AuditLog audit) {
        this.tickets = tickets;
        this.sessions = sessions;
        this.leases = leases;
        this.instances = instances;
        this.listeners = listeners;
        this.bridge = bridge;
        this.webSocketService = webSocketService;
        this.audit = audit;
    }

    /**
     * 唯一接受控制台跨端口 POST 的地址。票据先原子消费再校验；成功后签发本端口专属 Cookie 并 303 到 {@code /}。
     * 对外失败一律 {@code TICKET_REJECTED}（不区分原因），原因只进审计。
     */
    @PostMapping("/__mmab/session/exchange")
    public Mono<ResponseEntity<Object>> exchange(ServerWebExchange ex) {
        ListenerRegistry.Role role = Requests.role(ex);
        String origin = ex.getRequest().getHeaders().getOrigin();
        String requestOrigin = listeners.scheme() + "://" + ex.getRequest().getHeaders().getFirst(HttpHeaders.HOST);
        return ex.getFormData().map(form -> {
            List<String> values = form.get("ticket");
            String token = values == null || values.size() != 1 ? null : values.get(0);
            LaunchTicketService.Outcome outcome = tickets.exchange(token, origin, role.port(), requestOrigin);
            if (!outcome.ok()) {
                audit.record(null, "ticket.exchange", role.instanceId(), ErrorCode.TICKET_REJECTED.name(),
                        outcome.rejection().name());
                throw new ApiException(HttpStatus.FORBIDDEN, ErrorCode.TICKET_REJECTED);
            }
            LaunchTicketService.LaunchTicket t = outcome.ticket();
            Optional<ConsoleSession> parent = sessions.consoleById(t.consoleSessionId());
            boolean leaseOk = leases.isCurrent(t.instanceId(), t.leaseId(), t.fencingToken(), t.generation())
                    && t.generation() == instances.generation(t.instanceId());
            if (parent.isEmpty() || !leaseOk || !t.instanceId().equals(role.instanceId())) {
                audit.record(t.consoleSessionId(), "ticket.exchange", role.instanceId(),
                        ErrorCode.TICKET_REJECTED.name(), parent.isEmpty() ? "SESSION_REVOKED" : "LEASE_CHANGED");
                throw new ApiException(HttpStatus.FORBIDDEN, ErrorCode.TICKET_REJECTED);
            }
            SessionService.Created<InstanceSession> created = sessions.createInstance(parent.get(), t.instanceId(),
                    role.port(), t.leaseId(), t.fencingToken(), t.generation());
            audit.record(created.session().id(), "ticket.exchange", role.instanceId(), "OK",
                    "generation=" + t.generation());
            return ResponseEntity.status(HttpStatus.SEE_OTHER)
                    .location(URI.create("/"))
                    .header(HttpHeaders.SET_COOKIE, Requests.sessionCookie(
                            MmabAttributes.instanceCookie(role.instanceId()), created.token()).toString())
                    .build();
        });
    }

    @PostMapping("/__mmab/session/logout")
    public ResponseEntity<Void> logout(ServerWebExchange ex) {
        InstanceSession s = Requests.instanceSession(ex);
        sessions.revokeInstance(s, ErrorCode.UNAUTHENTICATED);
        leases.release(s.leaseId(), s.parentSessionId(), ErrorCode.LEASE_REQUIRED);
        audit.record(s.id(), "instance.logout", s.instanceId(), "OK", null);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE,
                        Requests.expiredCookie(MmabAttributes.instanceCookie(s.instanceId())).toString())
                .build();
    }

    /** 桥接引导数据：敏感资源，GuardFilter 已要求 Sec-Fetch-Site: same-origin，响应带 CORP same-origin。 */
    @GetMapping("/__mmab/bridge/bootstrap")
    public Map<String, Object> bootstrap(ServerWebExchange ex) {
        InstanceSession s = Requests.instanceSession(ex);
        InstanceRuntime rt = instances.get(s.instanceId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND));
        AdapterDescriptor d = rt.descriptor();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("instanceId", s.instanceId());
        m.put("profileId", d.profileId());
        m.put("protocolVersion", AdapterProtocol.CURRENT.toString());
        m.put("codecVersion", AdapterProtocol.CODEC_VERSION);
        m.put("generation", s.generation());
        m.put("wsPath", "/__mmab/bridge/ws");
        m.put("heartbeatSeconds", 15);
        m.put("maxFrameBytes", AdapterProtocol.MAX_FRAME_BYTES);
        m.put("operations", d.operations().values().stream()
                .filter(o -> o.kind() == AdapterDescriptor.OperationKind.REQUEST)
                .filter(o -> d.state(o.capability()).enabledByDefault())
                .map(AdapterDescriptor.OperationSpec::name).sorted().toList());
        return m;
    }

    @GetMapping("/__mmab/bridge/ws")
    public Mono<Void> websocket(ServerWebExchange ex) {
        InstanceSession s = Requests.instanceSession(ex);
        return webSocketService.handleRequest(ex, ws -> bridge.serve(ws, s));
    }
}
