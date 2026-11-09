package io.github.dipinllx.macminiappbridge.api;

import io.github.dipinllx.macminiappbridge.audit.AuditLog;
import io.github.dipinllx.macminiappbridge.identity.ConsoleSession;
import io.github.dipinllx.macminiappbridge.identity.OperatorAuthenticator;
import io.github.dipinllx.macminiappbridge.identity.SessionService;
import io.github.dipinllx.macminiappbridge.leases.LeaseManager;
import io.github.dipinllx.macminiappbridge.protocol.ErrorCode;
import io.github.dipinllx.macminiappbridge.web.ApiException;
import io.github.dipinllx.macminiappbridge.web.MmabAttributes;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** 控制台登录 / 注销 / 会话查询（只在控制台端口生效）。 */
@RestController
@RequestMapping("/__mmab/api/v1/auth")
public class AuthController {

    /** @param password 平台操作者口令（与应用账号无关，不落日志） */
    public record LoginRequest(String password) {
        @Override
        public String toString() {
            return "LoginRequest[password=***]";
        }
    }

    private final OperatorAuthenticator authenticator;
    private final SessionService sessions;
    private final LeaseManager leases;
    private final AuditLog audit;

    public AuthController(OperatorAuthenticator authenticator, SessionService sessions, LeaseManager leases,
            AuditLog audit) {
        this.authenticator = authenticator;
        this.sessions = sessions;
        this.leases = leases;
        this.audit = audit;
    }

    @PostMapping("/login")
    public Mono<ResponseEntity<Map<String, Object>>> login(@RequestBody LoginRequest body, ServerWebExchange ex) {
        String origin = ex.getRequest().getHeaders().getOrigin();
        char[] pw = body == null || body.password() == null ? new char[0] : body.password().toCharArray();
        return Mono.fromCallable(() -> {
            try {
                return authenticator.verify(pw);
            } finally {
                Arrays.fill(pw, '\0');
            }
        }).subscribeOn(Schedulers.boundedElastic()).map(rejection -> {
            if (rejection.isPresent()) {
                audit.record(null, "auth.login", null, rejection.get().name(), null);
                HttpStatus status = switch (rejection.get()) {
                    case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
                    case SETUP_REQUIRED -> HttpStatus.SERVICE_UNAVAILABLE;
                    default -> HttpStatus.UNAUTHORIZED;
                };
                throw new ApiException(status, rejection.get());
            }
            // 防会话固定：登录总是换新会话，旧会话（若有）作废
            Optional.ofNullable((ConsoleSession) ex.getAttribute(MmabAttributes.CONSOLE_SESSION))
                    .ifPresent(old -> logout(old, ErrorCode.UNAUTHENTICATED));
            SessionService.Created<ConsoleSession> created = sessions.createConsole(origin);
            audit.record(created.session().id(), "auth.login", null, "OK", null);
            return ResponseEntity.ok()
                    .header(HttpHeaders.SET_COOKIE,
                            Requests.sessionCookie(MmabAttributes.CONSOLE_COOKIE, created.token(),
                                    sessions.remaining(created.session().createdAt())).toString())
                    .body(sessionBody(created.session()));
        });
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(ServerWebExchange ex) {
        ConsoleSession s = Requests.consoleSession(ex);
        logout(s, ErrorCode.UNAUTHENTICATED);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, Requests.expiredCookie(MmabAttributes.CONSOLE_COOKIE).toString())
                .build();
    }

    /**
     * 注销所有会话（reliable-personal-entry：持久会话可随时整体撤销）。需要 CSRF（GuardFilter）并复核口令；
     * 所有控制台 / 实例会话失效，打开中的数据连接被关闭，各设备上的页面需要重新登录。
     */
    @PostMapping("/revoke-all")
    public Mono<ResponseEntity<Void>> revokeAll(@RequestBody LoginRequest body, ServerWebExchange ex) {
        ConsoleSession s = Requests.consoleSession(ex);
        char[] pw = body == null || body.password() == null ? new char[0] : body.password().toCharArray();
        return Mono.fromCallable(() -> {
            try {
                return authenticator.verify(pw);
            } finally {
                Arrays.fill(pw, '\0');
            }
        }).subscribeOn(Schedulers.boundedElastic()).map(rejection -> {
            if (rejection.isPresent()) {
                audit.record(s.id(), "auth.revoke-all", null, rejection.get().name(), null);
                throw new ApiException(rejection.get() == ErrorCode.RATE_LIMITED
                        ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.UNAUTHORIZED, rejection.get());
            }
            var revoked = sessions.revokeAll(ErrorCode.UNAUTHENTICATED);
            for (ConsoleSession c : revoked) {
                leases.releaseAllHeldBy(c.id(), ErrorCode.UNAUTHENTICATED);
            }
            audit.record(s.id(), "auth.revoke-all", null, "OK", "consoles=" + revoked.size());
            return ResponseEntity.noContent()
                    .header(HttpHeaders.SET_COOKIE, Requests.expiredCookie(MmabAttributes.CONSOLE_COOKIE).toString())
                    .<Void>build();
        });
    }

    @GetMapping("/session")
    public Map<String, Object> session(ServerWebExchange ex) {
        return sessionBody(Requests.consoleSession(ex));
    }

    private void logout(ConsoleSession s, ErrorCode reason) {
        leases.releaseAllHeldBy(s.id(), reason);
        sessions.logoutConsole(s, reason);
        audit.record(s.id(), "auth.logout", null, "OK", null);
    }

    private static Map<String, Object> sessionBody(ConsoleSession s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("authenticated", true);
        m.put("csrfToken", s.csrfToken());
        m.put("createdAt", s.createdAt().toString());
        return m;
    }

    static ApiException unauthenticated() {
        return new ApiException(HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHENTICATED);
    }
}
