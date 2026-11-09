package io.github.dipinllx.macminiappbridge.api;

import io.github.dipinllx.macminiappbridge.audit.AuditLog;
import io.github.dipinllx.macminiappbridge.config.MmabClock;
import io.github.dipinllx.macminiappbridge.config.MmabProperties;
import io.github.dipinllx.macminiappbridge.identity.Tokens;
import io.github.dipinllx.macminiappbridge.instances.InstanceAccess;
import io.github.dipinllx.macminiappbridge.instances.ProcessAdapterClient;
import io.github.dipinllx.macminiappbridge.protocol.Envelope;
import io.github.dipinllx.macminiappbridge.protocol.MessageKind;
import io.github.dipinllx.macminiappbridge.protocol.ProtocolException;
import io.github.dipinllx.macminiappbridge.protocol.StrictJson;
import io.github.dipinllx.macminiappbridge.protocol.codec.StructuredCodec;
import io.github.dipinllx.macminiappbridge.proxy.SyncCallRegistry;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import tools.jackson.databind.JsonNode;
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

    /** 同步调用的硬上限：阻塞浏览器主线程，不能用 30 秒的普通 deadline。 */
    static final java.time.Duration SYNC_DEADLINE = java.time.Duration.ofSeconds(10);

    private final InstanceAccess access;
    private final SyncCallRegistry syncCalls;
    private final MmabClock clock;
    private final java.time.Duration requestDeadline;
    private final StructuredCodec codec = new StructuredCodec();

    public InstancePortController(LaunchTicketService tickets, SessionService sessions, LeaseManager leases,
            InstanceRegistry instances, ListenerRegistry listeners, BridgeSocketHandler bridge,
            HandshakeWebSocketService webSocketService, AuditLog audit, InstanceAccess access,
            SyncCallRegistry syncCalls, MmabClock clock, MmabProperties properties) {
        this.access = access;
        this.syncCalls = syncCalls;
        this.clock = clock;
        this.requestDeadline = properties.timeouts().requestDeadline();
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
                .filter(o -> d.enabled(o.capability()))
                .map(AdapterDescriptor.OperationSpec::name).sorted().toList());
        return m;
    }

    /**
     * M3 同步调用（设计 §10.3"sync bootstrap 等非 WS 数据面同样受策略约束"）：浏览器 shim 用同步 XHR 调用
     * adapter.yaml 中声明 {@code sync: true} 的操作（启动引导快照、ipcRenderer.sendSync 类）。GuardFilter 已校验
     * 会话 Cookie、精确 Origin、Fetch Metadata 与 Content-Type；这里再做网关闸门、codec 校验（不允许携带端口）、
     * 租约 / generation 复核与 deadline。HTTP 200 + {@code {"payload"}} 或 {@code {"error"}}（与 WS RESPONSE 同形）。
     */
    /** reliable-personal-entry D1：浏览器断线诊断，只写审计（白名单元数据），204。 */
    @PostMapping("/__mmab/bridge/diag")
    public Mono<ResponseEntity<Void>> diag(ServerWebExchange ex) {
        InstanceSession s = Requests.instanceSession(ex);
        return DataBufferUtils.join(ex.getRequest().getBody(), io.github.dipinllx.macminiappbridge.proxy.BrowserDiagnostics.MAX_BODY_BYTES)
                .map(buf -> {
                    try {
                        return buf.toString(java.nio.charset.StandardCharsets.UTF_8);
                    } finally {
                        DataBufferUtils.release(buf);
                    }
                })
                .onErrorMap(DataBufferLimitException.class,
                        e -> new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, ErrorCode.QUEUE_LIMIT))
                .defaultIfEmpty("")
                .map(body -> {
                    JsonNode n;
                    try {
                        n = StrictJson.WIRE.parse(body);
                    } catch (ProtocolException e) {
                        throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.MALFORMED_FRAME);
                    }
                    var parsed = io.github.dipinllx.macminiappbridge.proxy.BrowserDiagnostics.parse(n)
                            .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.MALFORMED_FRAME));
                    audit.record(s.id(), "browser.diag", s.instanceId(), parsed.getKey(), parsed.getValue());
                    return ResponseEntity.noContent().<Void>build();
                });
    }

    @PostMapping("/__mmab/bridge/call")
    public Mono<ResponseEntity<Map<String, Object>>> call(ServerWebExchange ex) {
        InstanceSession s = Requests.instanceSession(ex);
        return DataBufferUtils.join(ex.getRequest().getBody(), AdapterProtocol.MAX_FRAME_BYTES + 4096)
                .map(buf -> {
                    try {
                        return buf.toString(java.nio.charset.StandardCharsets.UTF_8);
                    } finally {
                        DataBufferUtils.release(buf);
                    }
                })
                .onErrorMap(DataBufferLimitException.class,
                        e -> new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, ErrorCode.QUEUE_LIMIT))
                .defaultIfEmpty("")
                .flatMap(body -> syncCall(s, body))
                .map(r -> ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore()).body(r));
    }

    private Mono<Map<String, Object>> syncCall(InstanceSession s, String body) {
        JsonNode frame;
        try {
            frame = StrictJson.WIRE.parse(body);
        } catch (ProtocolException e) {
            return Mono.just(error(ErrorCode.MALFORMED_FRAME));
        }
        if (!frame.isObject() || frame.size() != 2 || frame.get("op") == null || !frame.get("op").isString()
                || frame.get("payload") == null || !frame.get("payload").isString()) {
            return Mono.just(error(ErrorCode.MALFORMED_FRAME));
        }
        String op = frame.get("op").stringValue();
        String payload = frame.get("payload").stringValue();
        InstanceRuntime rt = instances.get(s.instanceId()).orElse(null);
        if (rt == null || rt.descriptor() == null) {
            return Mono.just(error(ErrorCode.ADAPTER_UNAVAILABLE));
        }
        AdapterDescriptor d = rt.descriptor();
        Optional<ErrorCode> gate = d.gateSyncRequest(op);
        if (gate.isPresent()) {
            audit.record(s.id(), "bridge.sync", s.instanceId(), gate.get().name(), "gateway");
            return Mono.just(error(gate.get()));
        }
        try {
            if (!codec.portIds(payload).isEmpty()) {
                return Mono.just(error(ErrorCode.CODEC_UNSUPPORTED)); // 同步调用没有连接，不能转移端口
            }
        } catch (ProtocolException e) {
            return Mono.just(error(e.code()));
        }
        ErrorCode invalid = access.check(s);
        if (invalid != null) {
            return Mono.just(error(invalid));
        }
        ProcessAdapterClient client = rt.client();
        if (client == null || !rt.ready()) {
            return Mono.just(error(ErrorCode.ADAPTER_UNAVAILABLE));
        }
        String connectionId = SyncCallRegistry.PREFIX + Tokens.shortId("");
        String correlationId = connectionId + "/1";
        java.util.concurrent.CompletableFuture<Envelope> future =
                syncCalls.register(s.instanceId(), connectionId, correlationId);
        if (future == null) {
            return Mono.just(error(ErrorCode.QUEUE_LIMIT));
        }
        java.time.Duration deadline = requestDeadline.compareTo(SYNC_DEADLINE) < 0 ? requestDeadline : SYNC_DEADLINE;
        Envelope env = Envelope.builder()
                .profileId(d.profileId())
                .instanceId(s.instanceId())
                .processGeneration(s.generation())
                .connectionId(connectionId)
                .leaseId(s.leaseId())
                .fencingToken(s.fencingToken())
                .sequence(1)
                .correlationId(correlationId)
                .messageKind(MessageKind.REQUEST)
                .operation(op)
                .deadline(clock.instant().plus(deadline))
                .payload(payload)
                .build();
        return client.send(env)
                .then(Mono.fromFuture(future))
                .timeout(deadline)
                .map(r -> r.error() != null ? error(r.error().code()) : Map.<String, Object>of("payload",
                        r.payload()))
                .onErrorResume(java.util.concurrent.TimeoutException.class, t -> {
                    future.cancel(false);
                    return Mono.just(error(ErrorCode.DEADLINE_EXCEEDED)); // 执行状态未知，迟到结果丢弃
                })
                .onErrorResume(ProtocolException.class, t -> {
                    future.cancel(false);
                    return Mono.just(error(t.code()));
                });
    }

    private static Map<String, Object> error(ErrorCode code) {
        return Map.of("error", Map.of("code", code.name(), "message", code.summary()));
    }

    @GetMapping("/__mmab/bridge/ws")
    public Mono<Void> websocket(ServerWebExchange ex) {
        InstanceSession s = Requests.instanceSession(ex);
        return webSocketService.handleRequest(ex, ws -> bridge.serve(ws, s));
    }
}
