package io.github.dipinllx.macminiappbridge.web;

import io.github.dipinllx.macminiappbridge.audit.AuditLog;
import io.github.dipinllx.macminiappbridge.identity.ConsoleSession;
import io.github.dipinllx.macminiappbridge.identity.InstanceSession;
import io.github.dipinllx.macminiappbridge.identity.SessionService;
import io.github.dipinllx.macminiappbridge.identity.Tokens;
import io.github.dipinllx.macminiappbridge.instances.InstanceAccess;
import io.github.dipinllx.macminiappbridge.protocol.ErrorCode;
import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.StringJoiner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * 所有监听共用的第一道过滤器（设计 §5.2、§5.3、§6.3）：
 *
 * <ol>
 *   <li>按 socket 本地端口确定监听角色；未登记端口一律拒绝。</li>
 *   <li>Host 必须精确为登记主机 + 本端口（防 DNS rebinding）。</li>
 *   <li>不提供 CORS：预检与 OPTIONS 一律拒绝。</li>
 *   <li>控制台：写请求要求精确 Origin + CSRF 头；API 的 GET 要求 {@code Sec-Fetch-Site: same-origin}。</li>
 *   <li>实例：只有 {@code POST /__mmab/session/exchange} 接受控制台 Origin 的跨端口提交；其余资源要求
 *       本端口专属 Cookie 对应的有效会话；WS 升级要求精确的本端口 Origin；引导数据要求 same-origin。</li>
 *   <li>所有响应加 {@code Cross-Origin-Resource-Policy: same-origin}、nosniff、禁止 framing；Referrer-Policy 见下。</li>
 *   <li>Referrer-Policy：实例端口 {@code same-origin}；控制台端口 {@code strict-origin}——按 Fetch 规范，
 *       {@code same-origin} 策略下跨源请求的 Origin 头会被序列化为 {@code null}，控制台向实例端口提交开窗表单时
 *       exchange 便收不到控制台 Origin（真实 Chrome 实测 403）。{@code strict-origin} 只发送源、不带路径，
 *       HTTPS→HTTP 降级时不发送。
 *       nosniff、禁止 framing。</li>
 * </ol>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GuardFilter implements WebFilter {

    static final String API_PREFIX = "/__mmab/api/v1/";
    static final String EXCHANGE = "/__mmab/session/exchange";
    static final String INSTANCE_LOGOUT = "/__mmab/session/logout";
    static final String BOOTSTRAP = "/__mmab/bridge/bootstrap";
    static final String WS = "/__mmab/bridge/ws";
    static final String SHIM_PREFIX = "/__mmab/shim/";
    static final String APP_SHIM_PREFIX = "/__mmab/app-shim/";
    /** M3：同步调用端点（启动引导 / sendSync 类），只接受 adapter.yaml 中声明 sync 的操作。 */
    static final String SYNC_CALL = "/__mmab/bridge/call";
    /** reliable-personal-entry D1：浏览器断线诊断（sendBeacon），只收白名单元数据。 */
    static final String DIAG = "/__mmab/bridge/diag";
    static final String HEALTH = API_PREFIX + "health";
    static final String LOGIN = API_PREFIX + "auth/login";

    /** 421：请求打到了不属于它的监听（未登记端口 / Host 不符）。 */
    private static final HttpStatusCode MISDIRECTED = HttpStatusCode.valueOf(421);

    private final ListenerRegistry listeners;
    private final SessionService sessions;
    private final InstanceAccess access;
    private final AuditLog audit;

    public GuardFilter(ListenerRegistry listeners, SessionService sessions, InstanceAccess access, AuditLog audit) {
        this.listeners = listeners;
        this.sessions = sessions;
        this.access = access;
        this.audit = audit;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest req = exchange.getRequest();
        HttpHeaders out = exchange.getResponse().getHeaders();
        out.set("Referrer-Policy", "same-origin");
        out.set("X-Content-Type-Options", "nosniff");
        out.set("Cross-Origin-Resource-Policy", "same-origin");
        out.set("X-Frame-Options", "DENY");

        InetSocketAddress local = req.getLocalAddress();
        Optional<ListenerRegistry.Role> role = local == null ? Optional.empty() : listeners.resolve(local.getPort());
        if (role.isEmpty()) {
            return deny(exchange, MISDIRECTED, ErrorCode.NOT_FOUND);
        }
        int port = role.get().port();
        if (!listeners.hostAllowed(req.getHeaders().getFirst(HttpHeaders.HOST), port, local.getAddress())) {
            return deny(exchange, MISDIRECTED, ErrorCode.HOST_REJECTED);
        }
        exchange.getAttributes().put(MmabAttributes.ROLE, role.get());
        out.set("Referrer-Policy", role.get().console() ? "strict-origin" : "same-origin");
        if (HttpMethod.OPTIONS.equals(req.getMethod())) {
            return deny(exchange, HttpStatus.FORBIDDEN, ErrorCode.ORIGIN_REJECTED);
        }
        return role.get().console() ? console(exchange, chain, port) : instance(exchange, chain, role.get());
    }

    // ------------------------------------------------------------------ console listener

    private Mono<Void> console(ServerWebExchange exchange, WebFilterChain chain, int port) {
        ServerHttpRequest req = exchange.getRequest();
        exchange.getResponse().getHeaders().set("Content-Security-Policy", consoleCsp());
        String path = req.getPath().pathWithinApplication().value();
        boolean unsafe = isUnsafe(req.getMethod());
        if (path.startsWith("/__mmab/") && !path.startsWith(API_PREFIX)) {
            return deny(exchange, HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND);
        }
        if (!path.startsWith(API_PREFIX)) {
            if (unsafe) {
                return deny(exchange, HttpStatus.METHOD_NOT_ALLOWED, ErrorCode.METHOD_NOT_ALLOWED);
            }
            return chain.filter(exchange);
        }
        exchange.getResponse().getHeaders().set(HttpHeaders.CACHE_CONTROL, "no-store");
        if (path.equals(HEALTH) && !unsafe) {
            return chain.filter(exchange);
        }
        String fetchSite = req.getHeaders().getFirst("Sec-Fetch-Site");
        Optional<ConsoleSession> session = cookie(req, MmabAttributes.CONSOLE_COOKIE)
                .flatMap(sessions::resolveConsole);
        session.ifPresent(s -> exchange.getAttributes().put(MmabAttributes.CONSOLE_SESSION, s));
        if (unsafe) {
            String origin = req.getHeaders().getOrigin();
            if (!listeners.isOriginFor(origin, port)) {
                return deny(exchange, HttpStatus.FORBIDDEN, ErrorCode.ORIGIN_REJECTED);
            }
            if (fetchSite != null && !"same-origin".equals(fetchSite)) {
                return deny(exchange, HttpStatus.FORBIDDEN, ErrorCode.FETCH_SITE_REJECTED);
            }
            if (path.equals(LOGIN)) {
                return chain.filter(exchange);
            }
            if (session.isEmpty()) {
                return deny(exchange, HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHENTICATED);
            }
            if (!Tokens.constantTimeEquals(session.get().csrfToken(),
                    req.getHeaders().getFirst(MmabAttributes.CSRF_HEADER))) {
                return deny(exchange, HttpStatus.FORBIDDEN, ErrorCode.CSRF_REJECTED);
            }
            return chain.filter(exchange);
        }
        // 平台 API 的 JSON 是敏感资源：只允许同源加载（防其他端口页面 <script src> 跨端口读取）
        if (!"same-origin".equals(fetchSite)) {
            return deny(exchange, HttpStatus.FORBIDDEN, ErrorCode.FETCH_SITE_REJECTED);
        }
        if (session.isEmpty()) {
            return deny(exchange, HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHENTICATED);
        }
        return chain.filter(exchange);
    }

    private String consoleCsp() {
        StringJoiner formAction = new StringJoiner(" ");
        formAction.add("'self'");
        for (String host : listeners.hosts()) {
            listeners.registeredInstancePorts().forEach(p -> formAction.add(listeners.origin(host, p)));
        }
        return "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; "
                + "font-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'none'; "
                + "frame-ancestors 'none'; form-action " + formAction;
    }

    // ------------------------------------------------------------------ instance listener

    private Mono<Void> instance(ServerWebExchange exchange, WebFilterChain chain, ListenerRegistry.Role role) {
        ServerHttpRequest req = exchange.getRequest();
        HttpHeaders out = exchange.getResponse().getHeaders();
        out.set("Content-Security-Policy", "frame-ancestors 'none'; object-src 'none'");
        String path = req.getPath().pathWithinApplication().value();
        HttpMethod method = req.getMethod();
        String origin = req.getHeaders().getOrigin();
        String fetchSite = req.getHeaders().getFirst("Sec-Fetch-Site");

        if (path.equals(EXCHANGE)) {
            out.set(HttpHeaders.CACHE_CONTROL, "no-store");
            if (!HttpMethod.POST.equals(method)) {
                return deny(exchange, HttpStatus.METHOD_NOT_ALLOWED, ErrorCode.METHOD_NOT_ALLOWED);
            }
            // 唯一允许跨端口提交的地址：来源必须恰为控制台 Origin（null / 缺失一律拒绝）
            if (!listeners.isConsoleOrigin(origin)) {
                return deny(exchange, HttpStatus.FORBIDDEN, ErrorCode.ORIGIN_REJECTED);
            }
            if (fetchSite != null && !"same-site".equals(fetchSite) && !"same-origin".equals(fetchSite)) {
                return deny(exchange, HttpStatus.FORBIDDEN, ErrorCode.FETCH_SITE_REJECTED);
            }
            String mode = req.getHeaders().getFirst("Sec-Fetch-Mode");
            if (mode != null && !"navigate".equals(mode)) {
                return deny(exchange, HttpStatus.FORBIDDEN, ErrorCode.FETCH_SITE_REJECTED);
            }
            return chain.filter(exchange);
        }
        if (path.startsWith("/__mmab/api/")) {
            return deny(exchange, HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND);
        }
        boolean platformPath = path.startsWith("/__mmab/");
        if (platformPath && !path.equals(INSTANCE_LOGOUT) && !path.equals(BOOTSTRAP) && !path.equals(WS)
                && !path.equals(SYNC_CALL) && !path.equals(DIAG) && !path.startsWith(SHIM_PREFIX)
                && !path.startsWith(APP_SHIM_PREFIX)) {
            return deny(exchange, HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND);
        }

        Optional<InstanceSession> session = cookie(req, MmabAttributes.instanceCookie(role.instanceId()))
                .flatMap(token -> access.resolve(token, role.instanceId(), role.port()));
        if (session.isEmpty()) {
            if (!platformPath && (HttpMethod.GET.equals(method) || HttpMethod.HEAD.equals(method))) {
                return WebErrors.writeLoginHint(exchange);
            }
            return deny(exchange, HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHENTICATED);
        }
        exchange.getAttributes().put(MmabAttributes.INSTANCE_SESSION, session.get());

        if (path.equals(INSTANCE_LOGOUT)) {
            out.set(HttpHeaders.CACHE_CONTROL, "no-store");
            if (!HttpMethod.POST.equals(method)) {
                return deny(exchange, HttpStatus.METHOD_NOT_ALLOWED, ErrorCode.METHOD_NOT_ALLOWED);
            }
            if (!listeners.isOriginFor(origin, role.port())) {
                return deny(exchange, HttpStatus.FORBIDDEN, ErrorCode.ORIGIN_REJECTED);
            }
            if (fetchSite != null && !"same-origin".equals(fetchSite)) {
                return deny(exchange, HttpStatus.FORBIDDEN, ErrorCode.FETCH_SITE_REJECTED);
            }
            return chain.filter(exchange);
        }
        if (path.equals(SYNC_CALL)) {
            out.set(HttpHeaders.CACHE_CONTROL, "no-store");
            if (!HttpMethod.POST.equals(method)) {
                return deny(exchange, HttpStatus.METHOD_NOT_ALLOWED, ErrorCode.METHOD_NOT_ALLOWED);
            }
            // 与 WS 升级同级：精确的本端口 Origin（null / 缺失拒绝），Fetch Metadata 只允许同源
            if (!listeners.isOriginFor(origin, role.port())) {
                return deny(exchange, HttpStatus.FORBIDDEN, ErrorCode.ORIGIN_REJECTED);
            }
            if (fetchSite != null && !"same-origin".equals(fetchSite)) {
                return deny(exchange, HttpStatus.FORBIDDEN, ErrorCode.FETCH_SITE_REJECTED);
            }
            MediaType type = req.getHeaders().getContentType();
            if (type == null || !MediaType.APPLICATION_JSON.isCompatibleWith(type)) {
                return deny(exchange, HttpStatus.UNSUPPORTED_MEDIA_TYPE, ErrorCode.MALFORMED_FRAME);
            }
            return chain.filter(exchange);
        }
        if (path.equals(DIAG)) {
            out.set(HttpHeaders.CACHE_CONTROL, "no-store");
            if (!HttpMethod.POST.equals(method)) {
                return deny(exchange, HttpStatus.METHOD_NOT_ALLOWED, ErrorCode.METHOD_NOT_ALLOWED);
            }
            // sendBeacon 由页面自身发出：精确本端口 Origin，Fetch Metadata 只允许同源
            if (!listeners.isOriginFor(origin, role.port())) {
                return deny(exchange, HttpStatus.FORBIDDEN, ErrorCode.ORIGIN_REJECTED);
            }
            if (fetchSite != null && !"same-origin".equals(fetchSite)) {
                return deny(exchange, HttpStatus.FORBIDDEN, ErrorCode.FETCH_SITE_REJECTED);
            }
            MediaType type = req.getHeaders().getContentType();
            if (type == null || !(MediaType.TEXT_PLAIN.isCompatibleWith(type) || MediaType.APPLICATION_JSON.isCompatibleWith(type))) {
                return deny(exchange, HttpStatus.UNSUPPORTED_MEDIA_TYPE, ErrorCode.MALFORMED_FRAME);
            }
            return chain.filter(exchange);
        }
        if (isUnsafe(method)) {
            // 实例端口除 exchange / logout / 同步调用 / 诊断外不接受写方法（原版资源代理只读）
            return deny(exchange, HttpStatus.METHOD_NOT_ALLOWED, ErrorCode.METHOD_NOT_ALLOWED);
        }
        if (path.equals(BOOTSTRAP)) {
            out.set(HttpHeaders.CACHE_CONTROL, "no-store");
            if (!"same-origin".equals(fetchSite)) {
                return deny(exchange, HttpStatus.FORBIDDEN, ErrorCode.FETCH_SITE_REJECTED);
            }
        }
        if (path.equals(WS)) {
            if (!listeners.isOriginFor(origin, role.port())) {
                return deny(exchange, HttpStatus.FORBIDDEN, ErrorCode.ORIGIN_REJECTED);
            }
            if (fetchSite != null && !"same-origin".equals(fetchSite)) {
                return deny(exchange, HttpStatus.FORBIDDEN, ErrorCode.FETCH_SITE_REJECTED);
            }
        }
        return chain.filter(exchange);
    }

    // ------------------------------------------------------------------ helpers

    /** 拒绝并记审计（只记方法与错误码，不记路径参数、头部或 Cookie）。 */
    private Mono<Void> deny(ServerWebExchange exchange, HttpStatusCode status, ErrorCode code) {
        ListenerRegistry.Role role = exchange.getAttribute(MmabAttributes.ROLE);
        String where = role == null ? "unknown-listener" : role.console() ? "console" : role.instanceId();
        audit.record(null, "guard.deny", where, code.name(), String.valueOf(exchange.getRequest().getMethod()));
        return WebErrors.write(exchange, status, code);
    }

    private static boolean isUnsafe(HttpMethod m) {
        return !(HttpMethod.GET.equals(m) || HttpMethod.HEAD.equals(m));
    }

    /** 只读取本监听专属名字的 Cookie；同名多值时拒绝（防 Cookie 注入覆盖）。 */
    private static Optional<String> cookie(ServerHttpRequest req, String name) {
        var values = req.getCookies().get(name);
        if (values == null || values.size() != 1) {
            return Optional.empty();
        }
        HttpCookie c = values.get(0);
        return Optional.ofNullable(c.getValue()).filter(v -> !v.isEmpty());
    }
}
