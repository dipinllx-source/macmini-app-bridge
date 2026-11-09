package io.github.dipinllx.macminiappbridge.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dipinllx.macminiappbridge.MacminiAppBridgeApplication;
import io.github.dipinllx.macminiappbridge.TestSupport;
import io.github.dipinllx.macminiappbridge.audit.AuditLog;
import io.github.dipinllx.macminiappbridge.config.MmabClock;
import io.github.dipinllx.macminiappbridge.identity.PasswordHasher;
import io.github.dipinllx.macminiappbridge.identity.Tokens;
import io.github.dipinllx.macminiappbridge.instances.InstanceRegistry;
import io.github.dipinllx.macminiappbridge.leases.LeaseManager;
import io.github.dipinllx.macminiappbridge.protocol.ErrorCode;
import io.github.dipinllx.macminiappbridge.protocol.codec.StructuredCodec;
import io.github.dipinllx.macminiappbridge.protocol.codec.SvValue;
import io.github.dipinllx.macminiappbridge.proxy.TransportRegistry;
import io.github.dipinllx.macminiappbridge.web.ListenerRegistry;
import io.github.dipinllx.macminiappbridge.web.MaintenanceSweeper;
import java.math.BigInteger;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * M1 端到端离线链路（设计 §11 M1、§5.3、§5.5 的离线部分）：真实 TLS 多端口监听 + 两个 Node mock 适配器进程 +
 * 固定 fixture 页面。浏览器行为用 JDK HttpClient / WebSocket 按浏览器会发送的头部模拟（未在真实浏览器中执行）。
 */
class OfflineEndToEndTest {

    static final JsonMapper JSON = new JsonMapper();
    static final StructuredCodec CODEC = new StructuredCodec();
    static ConfigurableApplicationContext ctx;
    static HttpClient http;
    static String password;
    static ListenerRegistry listeners;
    static MmabClock clock;
    static LeaseManager leases;
    static InstanceRegistry instances;
    static TransportRegistry transports;
    static AuditLog audit;
    static MaintenanceSweeper sweeper;

    record Console(String cookie, String csrf) {
    }

    final List<Console> consoles = new ArrayList<>();
    final List<Ws> sockets = new ArrayList<>();

    @BeforeAll
    static void boot() throws Exception {
        TestTls tls = TestTls.create();
        password = Tokens.random();
        String hash = PasswordHasher.hash(password.toCharArray(), PasswordHasher.MIN_ITERATIONS);
        List<String> args = new ArrayList<>(List.of(
                "--mmab.home=" + TestSupport.repoRoot(),
                "--mmab.data-dir=" + java.nio.file.Files.createTempDirectory("mmab-e2e-data"),
                "--mmab.console.port=0",
                "--mmab.web.tls-bundle=mmabtest",
                "--spring.ssl.bundle.jks.mmabtest.keystore.location=file:" + tls.keystore,
                "--spring.ssl.bundle.jks.mmabtest.keystore.password=" + tls.storePassword,
                "--spring.ssl.bundle.jks.mmabtest.keystore.type=PKCS12",
                "--mmab.operator.password-hash=" + hash,
                "--mmab.testing.allow-test-adapters=true",
                "--mmab.testing.clock-control=true",
                "--mmab.timeouts.request-deadline=3s",
                // M1 慢客户端测试按设计起始值 2048 帧 / 4 MiB 验证（生产默认 16384 帧 / 32 MiB，见 MmabProperties.Transport）
                "--mmab.transport.outbound-max-frames=2048",
                "--mmab.transport.outbound-max-bytes=4194304",
                "--mmab.adapters[0].app-id=mock",
                "--mmab.adapters[0].descriptor=adapters/mock/adapter.yaml",
                "--mmab.adapters[0].command[0]=" + TestSupport.node(),
                "--mmab.adapters[0].command[1]=adapters/mock/driver/main.mjs",
                "--mmab.adapters[0].resources=adapters/mock/fixture"));
        for (int i = 0; i < 2; i++) {
            String p = "--mmab.instances[" + i + "].";
            args.addAll(List.of(p + "id=mock-0" + (i + 1), p + "app-id=mock", p + "display-name=Mock " + (i + 1),
                    p + "port=0", p + "strategy=test-mock", p + "enabled=true"));
        }
        ctx = new SpringApplicationBuilder(MacminiAppBridgeApplication.class).run(args.toArray(String[]::new));
        listeners = ctx.getBean(ListenerRegistry.class);
        clock = ctx.getBean(MmabClock.class);
        leases = ctx.getBean(LeaseManager.class);
        instances = ctx.getBean(InstanceRegistry.class);
        transports = ctx.getBean(TransportRegistry.class);
        audit = ctx.getBean(AuditLog.class);
        sweeper = ctx.getBean(MaintenanceSweeper.class);
        http = HttpClient.newBuilder().sslContext(tls.clientContext).version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build();
        assertThat(instances.require("mock-01").ready()).as("mock-01 adapter started").isTrue();
        assertThat(instances.require("mock-02").ready()).as("mock-02 adapter started").isTrue();
    }

    @AfterAll
    static void shutdown() {
        if (ctx != null) {
            ctx.close();
        }
    }

    @AfterEach
    void cleanup() {
        for (Ws w : sockets) {
            w.socket.abort();
        }
        for (Console c : consoles) {
            try {
                consolePost(c, "/__mmab/api/v1/auth/logout", "");
            } catch (Exception ignored) {
                // already logged out
            }
        }
        leases.revokeInstance("mock-01", ErrorCode.LEASE_REQUIRED);
        leases.revokeInstance("mock-02", ErrorCode.LEASE_REQUIRED);
    }

    // =================================================================== 7. end-to-end chain

    @Test
    void consoleTicketExchangeSessionLeaseOperationResponse() throws Exception {
        Console c = login();
        JsonNode ticket = ticket(c, "mock-01");
        assertThat(ticket.get("exchangeUrl").stringValue()).isEqualTo(origin("mock-01") + "/__mmab/session/exchange");
        assertThat(ticket.get("lease").get("fencingToken").asLong()).isPositive();

        HttpResponse<String> ex = exchange("mock-01", ticket.get("ticket").stringValue(), consoleOrigin());
        assertThat(ex.statusCode()).isEqualTo(303);
        assertThat(ex.headers().firstValue("location")).contains("/");
        String setCookie = ex.headers().allValues("set-cookie").get(0);
        assertThat(setCookie).startsWith("__Host-mmab-i-mock-01=")
                .contains("; Path=/").contains("; Secure").contains("; HttpOnly").contains("; SameSite=Strict")
                .containsPattern("; Max-Age=\\d+") // reliable-personal-entry：持久 Cookie，浏览器重启后仍在
                .doesNotContainIgnoringCase("domain=");
        String cookie = cookieValue(ex, "__Host-mmab-i-mock-01");

        HttpResponse<String> page = instanceGet("mock-01", "/", cookie, null);
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body()).contains("<script type=\"module\" src=\"/__mmab/shim/shim.mjs\"></script>")
                .contains("Mock 原版页面");
        assertThat(instanceGet("mock-01", "/app.js", cookie, null).statusCode()).isEqualTo(200);
        assertThat(instanceGet("mock-01", "/__mmab/shim/shim.mjs", cookie, null).statusCode()).isEqualTo(200);
        assertThat(instanceGet("mock-01", "/__mmab/shim/codec.mjs", cookie, null).statusCode()).isEqualTo(200);
        assertThat(instanceGet("mock-01", "/__mmab/shim/codec.test.mjs", cookie, null).statusCode()).isEqualTo(404);

        HttpResponse<String> boot = instanceGet("mock-01", "/__mmab/bridge/bootstrap", cookie, "same-origin");
        assertThat(boot.statusCode()).isEqualTo(200);
        JsonNode b = JSON.readTree(boot.body());
        assertThat(b.get("instanceId").stringValue()).isEqualTo("mock-01");
        assertThat(b.get("codecVersion").stringValue()).isEqualTo("mmab-sv1");
        assertThat(b.get("operations").toString()).contains("mock.echo").doesNotContain("mock.compose");
        assertThat(boot.body()).doesNotContain(ticket.get("lease").get("leaseId").stringValue());

        Ws ws = ws("mock-01", cookie, origin("mock-01"), true);
        Map<String, SvValue> fields = new LinkedHashMap<>();
        fields.put("text", new SvValue.SvString("你好，引桥"));
        fields.put("big", new SvValue.SvBigInt(BigInteger.TWO.pow(70)));
        fields.put("bytes", new SvValue.SvBytes(io.github.dipinllx.macminiappbridge.protocol.codec.BytesKind.Uint8Array,
                new byte[] {1, 2, 3}));
        SvValue value = new SvValue.SvObject(fields);
        ws.request("mock.echo", "1", CODEC.encode(value));
        JsonNode resp = ws.response("1");
        assertThat(resp.get("error")).isNull();
        assertThat(CODEC.decode(resp.get("payload").stringValue())).isEqualTo(value);

        ws.request("mock.add", "2", "{\"$t\":\"object\",\"v\":{\"a\":2,\"b\":40}}");
        assertThat(CODEC.decode(ws.response("2").get("payload").stringValue())).isEqualTo(new SvValue.SvNumber(42));

        assertThat(audit.recent(100)).anySatisfy(e -> {
            assertThat(e.action()).isEqualTo("ticket.exchange");
            assertThat(e.result()).isEqualTo("OK");
        });
        assertThat(audit.recent(200).toString()).doesNotContain(ticket.get("ticket").stringValue())
                .doesNotContain(cookie).doesNotContain(password);
    }

    // =================================================================== 1. tickets

    @Test
    void ticketReplayIsRejected() throws Exception {
        Console c = login();
        String t = ticket(c, "mock-01").get("ticket").stringValue();
        assertThat(exchange("mock-01", t, consoleOrigin()).statusCode()).isEqualTo(303);
        HttpResponse<String> replay = exchange("mock-01", t, consoleOrigin());
        assertThat(replay.statusCode()).isEqualTo(403);
        assertThat(replay.body()).contains("TICKET_REJECTED");
        assertThat(replay.headers().allValues("set-cookie")).isEmpty();
        assertThat(lastAudit("ticket.exchange").detail()).isEqualTo("UNKNOWN_OR_REPLAYED");
    }

    @Test
    void ticketExpiresAfterThirtySeconds() throws Exception {
        Console c = login();
        String t = ticket(c, "mock-01").get("ticket").stringValue();
        clock.advance(Duration.ofSeconds(31));
        HttpResponse<String> late = exchange("mock-01", t, consoleOrigin());
        assertThat(late.statusCode()).isEqualTo(403);
        assertThat(late.headers().allValues("set-cookie")).isEmpty();
        // 过期票据可能已被每秒一次的清扫移除：两种拒绝原因都表示“过期不可用”
        assertThat(lastAudit("ticket.exchange").detail()).isIn("EXPIRED", "UNKNOWN_OR_REPLAYED");
    }

    @Test
    void ticketIsBoundToConsoleOriginAndTargetPort() throws Exception {
        Console c = login();
        String t = ticket(c, "mock-01").get("ticket").stringValue();
        for (String bad : new String[] {null, "null", origin("mock-01"), origin("mock-02"),
                "http://127.0.0.1:" + listeners.consolePort(), consoleOrigin() + "/"}) {
            HttpResponse<String> r = exchange("mock-01", t, bad);
            assertThat(r.statusCode()).as("Origin %s", bad).isEqualTo(403);
            assertThat(r.body()).contains("ORIGIN_REJECTED");
        }
        // 换错端口：票据在 mock-02 的端口上被消费并拒绝，之后在正确端口也不能再用
        HttpResponse<String> wrongPort = exchange("mock-02", t, consoleOrigin());
        assertThat(wrongPort.statusCode()).isEqualTo(403);
        assertThat(lastAudit("ticket.exchange").detail()).isEqualTo("WRONG_PORT");
        assertThat(exchange("mock-01", t, consoleOrigin()).statusCode()).isEqualTo(403);
        // 浏览器 fetch()（非导航）提交也拒绝
        String t2 = ticket(c, "mock-01").get("ticket").stringValue();
        HttpResponse<String> fetchMode = http.send(HttpRequest.newBuilder(instance("mock-01", "/__mmab/session/exchange"))
                .header("Origin", consoleOrigin()).header("Sec-Fetch-Site", "same-site").header("Sec-Fetch-Mode", "cors")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("ticket=" + t2)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(fetchMode.statusCode()).isEqualTo(403);
    }

    // =================================================================== 2. cookie host isolation

    @Test
    void sessionCookiesAreIsolatedPerPort() throws Exception {
        Console c = login();
        String c1 = openSession(c, "mock-01");
        String c2 = openSession(c, "mock-02");
        assertThat(instanceGet("mock-01", "/", c1, null).statusCode()).isEqualTo(200);
        assertThat(instanceGet("mock-02", "/", c2, null).statusCode()).isEqualTo(200);

        // 浏览器会把同 IP 的全部 Cookie 发给每个端口：别的端口的 Cookie 名不被读取
        HttpResponse<String> foreignName = rawGet(instance("mock-02", "/"), "__Host-mmab-i-mock-01=" + c1, null);
        assertThat(foreignName.statusCode()).isEqualTo(401);
        assertThat(foreignName.body()).contains("需要从控制台开窗");
        // 把 mock-01 的会话值改名成 mock-02 的 Cookie 名也没用：会话绑定实例与端口
        assertThat(rawGet(instance("mock-02", "/"), "__Host-mmab-i-mock-02=" + c1, null).statusCode()).isEqualTo(401);
        // 控制台会话不能当实例会话，实例会话也不能当控制台会话
        assertThat(rawGet(instance("mock-01", "/"), "__Host-mmab-i-mock-01=" + c.cookie(), null).statusCode())
                .isEqualTo(401);
        assertThat(rawGet(console("/__mmab/api/v1/instances"), "__Host-mmab-console=" + c1, "same-origin").statusCode())
                .isEqualTo(401);
        // 同名 Cookie 注入两份：拒绝
        assertThat(rawGet(instance("mock-01", "/"), "__Host-mmab-i-mock-01=" + c1 + "; __Host-mmab-i-mock-01=x", null)
                .statusCode()).isEqualTo(401);
        // 没有 Cookie：只见登录提示
        assertThat(rawGet(instance("mock-01", "/"), null, null).statusCode()).isEqualTo(401);
        // A 端口的页面以 B 端口身份建立 WS：Origin 不符被拒
        assertThat(wsStatus("mock-02", c2, origin("mock-01"))).isEqualTo(403);
        assertThat(wsStatus("mock-01", c1, null)).isEqualTo(403);
        assertThat(wsStatus("mock-01", c1, "null")).isEqualTo(403);
    }

    // =================================================================== 3. Origin / CSRF / Fetch Metadata

    @Test
    void consoleWritesRequireExactOriginAndCsrf() throws Exception {
        Console c = login();
        String path = "/__mmab/api/v1/instances/mock-01/launch-tickets";
        for (String bad : new String[] {null, "null", origin("mock-01"), "http://127.0.0.1:" + listeners.consolePort()}) {
            HttpRequest.Builder b = HttpRequest.newBuilder(console(path)).POST(HttpRequest.BodyPublishers.noBody())
                    .header("Cookie", "__Host-mmab-console=" + c.cookie()).header("X-MMAB-CSRF", c.csrf());
            if (bad != null) {
                b.header("Origin", bad);
            }
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            assertThat(r.statusCode()).as("Origin %s", bad).isEqualTo(403);
            assertThat(r.body()).contains("ORIGIN_REJECTED");
        }
        HttpResponse<String> noCsrf = http.send(HttpRequest.newBuilder(console(path))
                .POST(HttpRequest.BodyPublishers.noBody()).header("Origin", consoleOrigin())
                .header("Cookie", "__Host-mmab-console=" + c.cookie()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(noCsrf.statusCode()).isEqualTo(403);
        assertThat(noCsrf.body()).contains("CSRF_REJECTED");
        HttpResponse<String> wrongCsrf = http.send(HttpRequest.newBuilder(console(path))
                .POST(HttpRequest.BodyPublishers.noBody()).header("Origin", consoleOrigin())
                .header("X-MMAB-CSRF", Tokens.random())
                .header("Cookie", "__Host-mmab-console=" + c.cookie()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(wrongCsrf.body()).contains("CSRF_REJECTED");
        HttpResponse<String> crossSite = http.send(HttpRequest.newBuilder(console(path))
                .POST(HttpRequest.BodyPublishers.noBody()).header("Origin", consoleOrigin())
                .header("X-MMAB-CSRF", c.csrf()).header("Sec-Fetch-Site", "cross-site")
                .header("Cookie", "__Host-mmab-console=" + c.cookie()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(crossSite.body()).contains("FETCH_SITE_REJECTED");
        assertThat(audit.recent(300)).as("guard rejections are audited").anySatisfy(e -> {
            assertThat(e.action()).isEqualTo("guard.deny");
            assertThat(e.result()).isEqualTo("CSRF_REJECTED");
        });
        assertThat(consolePost(c, path, "").statusCode()).isEqualTo(201);

        // 平台 API 的 JSON 是敏感资源：只允许 Sec-Fetch-Site: same-origin
        assertThat(rawGet(console("/__mmab/api/v1/instances"), "__Host-mmab-console=" + c.cookie(), null).body())
                .contains("FETCH_SITE_REJECTED");
        assertThat(rawGet(console("/__mmab/api/v1/instances"), "__Host-mmab-console=" + c.cookie(), "same-site").body())
                .contains("FETCH_SITE_REJECTED");
        HttpResponse<String> ok = rawGet(console("/__mmab/api/v1/instances"), "__Host-mmab-console=" + c.cookie(),
                "same-origin");
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(ok.headers().firstValue("cross-origin-resource-policy")).contains("same-origin");
        assertThat(ok.body()).contains("mock-01").doesNotContain("adapterPort");

        // 无 CORS：预检一律拒绝
        HttpResponse<String> preflight = http.send(HttpRequest.newBuilder(console(path))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).header("Origin", origin("mock-01"))
                .header("Access-Control-Request-Method", "POST").build(), HttpResponse.BodyHandlers.ofString());
        assertThat(preflight.statusCode()).isEqualTo(403);
        assertThat(preflight.headers().firstValue("access-control-allow-origin")).isEmpty();

        // 登录本身也要求精确 Origin
        HttpResponse<String> login = http.send(HttpRequest.newBuilder(console("/__mmab/api/v1/auth/login"))
                .header("Origin", origin("mock-01")).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of("password", password))))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).isEqualTo(403);
    }

    @Test
    void sensitiveInstanceResourcesRequireSameOriginAndHostIsExact() throws Exception {
        Console c = login();
        String cookie = openSession(c, "mock-01");
        HttpResponse<String> crossPort = instanceGet("mock-01", "/__mmab/bridge/bootstrap", cookie, "same-site");
        assertThat(crossPort.statusCode()).as("<script src> from another port").isEqualTo(403);
        assertThat(crossPort.body()).contains("FETCH_SITE_REJECTED");
        assertThat(instanceGet("mock-01", "/__mmab/bridge/bootstrap", cookie, null).statusCode()).isEqualTo(403);
        HttpResponse<String> ok = instanceGet("mock-01", "/__mmab/bridge/bootstrap", cookie, "same-origin");
        assertThat(ok.headers().firstValue("cross-origin-resource-policy")).contains("same-origin");
        assertThat(ok.headers().firstValue("cache-control")).contains("no-store");
        // 实例端口不提供平台 API；控制台端口不提供实例端点
        assertThat(instanceGet("mock-01", "/__mmab/api/v1/health", cookie, "same-origin").statusCode()).isEqualTo(404);
        assertThat(rawGet(console("/__mmab/bridge/bootstrap"), null, "same-origin").statusCode()).isEqualTo(404);
        // Host 不是登记地址（DNS rebinding）
        HttpResponse<String> rebinding = http.send(HttpRequest.newBuilder(instance("mock-01", "/"))
                .header("Host", "attacker.example:" + listeners.instancePort("mock-01").orElseThrow())
                .header("Cookie", "__Host-mmab-i-mock-01=" + cookie).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(rebinding.statusCode()).isEqualTo(421);
        assertThat(rebinding.body()).contains("HOST_REJECTED");
        // 实例端口只读：写方法拒绝
        HttpResponse<String> put = http.send(HttpRequest.newBuilder(instance("mock-01", "/app.js"))
                .PUT(HttpRequest.BodyPublishers.ofString("x")).header("Origin", origin("mock-01"))
                .header("Cookie", "__Host-mmab-i-mock-01=" + cookie).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(put.statusCode()).isEqualTo(405);
        // 路径穿越
        assertThat(instanceGet("mock-01", "/..%2f..%2fpom.xml", cookie, null).statusCode()).isEqualTo(404);
    }

    @Test
    void securityHeadersOnBothListeners() throws Exception {
        Console c = login();
        String cookie = openSession(c, "mock-01");
        HttpResponse<String> page = instanceGet("mock-01", "/", cookie, null);
        assertThat(page.headers().firstValue("referrer-policy")).contains("same-origin");
        assertThat(page.headers().firstValue("cross-origin-resource-policy")).contains("same-origin");
        assertThat(page.headers().firstValue("x-content-type-options")).contains("nosniff");
        assertThat(page.headers().firstValue("x-frame-options")).contains("DENY");
        assertThat(page.headers().firstValue("content-security-policy").orElse("")).contains("frame-ancestors 'none'");
        HttpResponse<String> consolePage = rawGet(console("/"), null, null);
        // 控制台页必须让浏览器在跨端口开窗表单里带上控制台 Origin：same-origin / no-referrer 会让它变成 null
        assertThat(consolePage.headers().firstValue("referrer-policy")).contains("strict-origin");
        assertThat(consolePage.headers().firstValue("content-security-policy").orElse(""))
                .contains("form-action 'self' ").contains(origin("mock-01")).contains(origin("mock-02"))
                .contains("frame-ancestors 'none'");
    }

    // =================================================================== 4. protocol gate

    @Test
    void unknownOperationsDisabledCapabilitiesAndBadPayloadsAreRejected() throws Exception {
        Console c = login();
        Ws ws = ws("mock-01", openSession(c, "mock-01"), origin("mock-01"), true);
        String payload = "{\"$t\":\"undef\"}";
        ws.request("mock.nope", "a", payload);
        assertThat(ws.errorOf("a")).isEqualTo("UNKNOWN_OPERATION");
        ws.request("mock.compose", "b", payload);
        assertThat(ws.errorOf("b")).isEqualTo("CAPABILITY_DISABLED");
        ws.request("mmab.fence", "c", payload);
        assertThat(ws.errorOf("c")).isEqualTo("UNKNOWN_OPERATION");
        ws.request("mock.chunk", "d", payload);
        assertThat(ws.errorOf("d")).as("event names cannot be requested").isEqualTo("UNKNOWN_OPERATION");
        ws.request("mock.echo", "e", "{\"a\":1}");
        assertThat(ws.errorOf("e")).isEqualTo("MALFORMED_FRAME");
        ws.request("mock.echo", "f", "{\"$t\":\"object\",\"v\":{\"__proto__\":1}}");
        assertThat(ws.errorOf("f")).isEqualTo("CODEC_UNSUPPORTED");
        ws.send("{\"kind\":\"REQUEST\",\"op\":\"mock.echo\",\"cid\":\"g\",\"payload\":\"1\",\"leaseId\":\"l-x\"}");
        assertThat(ws.nextOfKind("ERROR").get("error").get("code").stringValue())
                .as("browser cannot supply lease / fence fields").isEqualTo("MALFORMED_FRAME");
        ws.send("not json");
        assertThat(ws.nextOfKind("ERROR").get("error").get("code").stringValue()).isEqualTo("MALFORMED_FRAME");
        ws.request("mock.echo", "h", "7");
        assertThat(CODEC.decode(ws.response("h").get("payload").stringValue())).as("connection still usable")
                .isEqualTo(new SvValue.SvNumber(7));
        assertThat(audit.recent(200)).anySatisfy(e -> assertThat(e.result()).isEqualTo("CAPABILITY_DISABLED"));
    }

    @Test
    void deadlineIsReportedAsUnknownNotFaked() throws Exception {
        Console c = login();
        Ws ws = ws("mock-02", openSession(c, "mock-02"), origin("mock-02"), true);
        long start = System.nanoTime();
        ws.request("mock.slow", "s", "{\"$t\":\"object\",\"v\":{\"ms\":5000}}");
        assertThat(ws.errorOf("s")).isEqualTo("DEADLINE_EXCEEDED");
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isBetween(2_500L, 4_900L);
        ws.request("mock.echo", "t", "1");
        assertThat(ws.response("t").get("error")).isNull();
        Thread.sleep(2_500);
        assertThat(ws.drain()).as("the late result is dropped, not delivered as success")
                .noneMatch(f -> "s".equals(f.path("cid").asString("")));
    }

    // =================================================================== 5. lease + generation fencing

    @Test
    void leaseIsExclusiveAcrossConsolesAndTabs() throws Exception {
        Console a = login();
        Console b = login();
        String cookie = openSession(a, "mock-01");
        HttpResponse<String> other = consolePost(b, "/__mmab/api/v1/instances/mock-01/launch-tickets", "");
        assertThat(other.statusCode()).isEqualTo(409);
        assertThat(other.body()).contains("LEASE_OCCUPIED");
        assertThat(consolePost(b, "/__mmab/api/v1/instances/mock-01/leases", "").statusCode()).isEqualTo(409);

        Ws first = ws("mock-01", cookie, origin("mock-01"), true);
        Ws second = ws("mock-01", cookie, origin("mock-01"), true);
        assertThat(second.closeCode.get(5, TimeUnit.SECONDS)).isEqualTo(1013);
        assertThat(second.closeReason).isEqualTo("LEASE_OCCUPIED");
        first.request("mock.echo", "1", "true");
        assertThat(first.response("1").get("error")).as("first tab unaffected").isNull();
    }

    @Test
    void generationBumpFencesOldSessionsTicketsAndFrames() throws Exception {
        Console c = login();
        String cookie = openSession(c, "mock-01");
        String pendingTicket = ticket(c, "mock-01").get("ticket").stringValue();
        String leaseId = leases.current("mock-01").orElseThrow().leaseId();
        Ws ws = ws("mock-01", cookie, origin("mock-01"), true);
        ws.request("mock.echo", "1", "1");
        assertThat(ws.response("1").get("error")).isNull();
        long before = instances.generation("mock-01");

        long after = instances.restartAdapter("mock-01");
        assertThat(after).isEqualTo(before + 1);
        assertThat(ws.closeCode.get(5, TimeUnit.SECONDS)).isEqualTo(1008);
        assertThat(ws.closeReason).isEqualTo("STALE_GENERATION");
        HttpResponse<String> hb = consolePost(c, "/__mmab/api/v1/leases/" + leaseId + "/heartbeat", "");
        assertThat(hb.statusCode()).isEqualTo(409);
        assertThat(hb.body()).contains("STALE_GENERATION");
        assertThat(exchange("mock-01", pendingTicket, consoleOrigin()).statusCode())
                .as("ticket issued for the old generation").isEqualTo(403);

        // reliable-personal-entry：同一实例会话在新 generation 下重新绑定租约（旧连接 / 旧帧仍被 fence 拒绝）
        JsonNode boot = JSON.readTree(instanceGet("mock-01", "/__mmab/bridge/bootstrap", cookie, "same-origin").body());
        assertThat(boot.get("generation").asLong()).isEqualTo(after);
        assertThat(leases.current("mock-01").orElseThrow().leaseId()).isNotEqualTo(leaseId);
        Ws ws2 = ws("mock-01", cookie, origin("mock-01"), true);
        ws2.request("mock.echo", "2", "2");
        assertThat(ws2.response("2").get("error")).isNull();
        String fresh = openSession(c, "mock-01");
        Ws ws3 = ws("mock-01", fresh, origin("mock-01"), true);
        assertThat(ws3.closeCode.get(5, TimeUnit.SECONDS)).as("second tab still occupied").isEqualTo(1013);
    }

    @Test
    void consoleLogoutCascadesToInstanceSessionAndTransport() throws Exception {
        Console c = login();
        String cookie = openSession(c, "mock-01");
        Ws ws = ws("mock-01", cookie, origin("mock-01"), true);
        assertThat(consolePost(c, "/__mmab/api/v1/auth/logout", "").statusCode()).isEqualTo(204);
        assertThat(ws.closeCode.get(5, TimeUnit.SECONDS)).isEqualTo(1008);
        assertThat(instanceGet("mock-01", "/", cookie, null).statusCode()).isEqualTo(401);
        Console other = login();
        assertThat(consolePost(other, "/__mmab/api/v1/instances/mock-01/leases", "").statusCode())
                .as("lease released").isEqualTo(201);
    }

    @Test
    void leaseExpiryClosesTransportButTheSessionRebindsToAFreshLease() throws Exception {
        // reliable-personal-entry：租约到期只关闭旧连接；会话仍有效，下次访问以父控制台会话重新获取新租约
        Console c = login();
        String cookie = openSession(c, "mock-02");
        Ws ws = ws("mock-02", cookie, origin("mock-02"), true);
        String oldLease = leases.current("mock-02").orElseThrow().leaseId();
        long oldFence = leases.current("mock-02").orElseThrow().fencingToken();
        clock.advance(Duration.ofSeconds(61));
        sweeper.sweepNow();
        assertThat(ws.closeCode.get(5, TimeUnit.SECONDS)).isEqualTo(1008);
        assertThat(ws.closeReason).isEqualTo("LEASE_REQUIRED");
        assertThat(instanceGet("mock-02", "/", cookie, null).statusCode()).as("session rebinds").isEqualTo(200);
        var fresh = leases.current("mock-02").orElseThrow();
        assertThat(fresh.leaseId()).isNotEqualTo(oldLease);
        assertThat(fresh.fencingToken()).isGreaterThan(oldFence);
        Ws again = ws("mock-02", cookie, origin("mock-02"), true);
        again.request("mock.echo", "r", "1");
        assertThat(again.response("r").get("error")).isNull();
    }

    @Test
    void revokeAllNeedsThePasswordAndEndsEverySessionAndConnection() throws Exception {
        Console a = login();
        Console b = login();
        String cookie = openSession(a, "mock-02");
        Ws ws = ws("mock-02", cookie, origin("mock-02"), true);
        HttpResponse<String> wrong = consolePost(b, "/__mmab/api/v1/auth/revoke-all",
                JSON.writeValueAsString(Map.of("password", "not-the-password")));
        assertThat(wrong.statusCode()).isEqualTo(401);
        assertThat(instanceGet("mock-02", "/", cookie, null).statusCode()).isEqualTo(200);
        HttpResponse<String> ok = consolePost(b, "/__mmab/api/v1/auth/revoke-all",
                JSON.writeValueAsString(Map.of("password", password)));
        assertThat(ok.statusCode()).isEqualTo(204);
        assertThat(ws.closeCode.get(5, TimeUnit.SECONDS)).isEqualTo(1008);
        assertThat(instanceGet("mock-02", "/", cookie, null).statusCode()).isEqualTo(401);
        for (Console x : List.of(a, b)) {
            assertThat(rawGet(console("/__mmab/api/v1/auth/session"), "__Host-mmab-console=" + x.cookie(), "same-origin")
                    .statusCode()).isEqualTo(401);
        }
        consoles.removeAll(List.of(a, b));
    }

    @Test
    void droppedTransportResumesLosslesslyOnlyWithinTheGraceWindow() throws Exception {
        // reliable-personal-entry D2：网络中断（无关闭帧）后连接挂起；同一页面 40 秒内以 ?resume= 重连无损恢复
        Console c = login();
        String cookie = openSession(c, "mock-02");
        Ws ws = ws("mock-02", cookie, origin("mock-02"), true);
        JsonNode hello = ws.nextOfKind("HELLO");
        assertThat(hello.get("resumed").asBoolean()).isFalse();
        String id = hello.get("connectionId").stringValue();
        ws.socket.abort();
        waitFor(() -> transports.suspended(id).isPresent());

        Ws back = ws("mock-02", cookie, origin("mock-02"), true, "?resume=" + id);
        JsonNode h2 = back.nextOfKind("HELLO");
        assertThat(h2.get("resumed").asBoolean()).isTrue();
        assertThat(h2.get("connectionId").stringValue()).isEqualTo(id);
        back.request("mock.echo", "r1", "\"after resume\"");
        assertThat(back.response("r1").get("error")).isNull();
        assertThat(audit.recent(50)).anySatisfy(e -> assertThat(e.action()).isEqualTo("transport.resume"));

        // grace window over: the suspended connection is closed for good and cannot be resumed
        back.socket.abort();
        waitFor(() -> transports.suspended(id).isPresent());
        clock.advance(Duration.ofSeconds(41));
        sweeper.sweepNow();
        assertThat(transports.suspended(id)).isEmpty();
        Ws late = ws("mock-02", cookie, origin("mock-02"), true, "?resume=" + id);
        JsonNode h3 = late.nextOfKind("HELLO");
        assertThat(h3.get("resumed").asBoolean()).as("too late: fresh connection, page must re-initialise").isFalse();
        assertThat(h3.get("connectionId").stringValue()).isNotEqualTo(id);

        // a new page (no resume id) supersedes a suspended connection instead of being told "occupied"
        late.socket.abort();
        String lateId = h3.get("connectionId").stringValue();
        waitFor(() -> transports.suspended(lateId).isPresent());
        Ws fresh = ws("mock-02", cookie, origin("mock-02"), true);
        assertThat(fresh.nextOfKind("HELLO").get("resumed").asBoolean()).isFalse();
        assertThat(transports.suspended(lateId)).isEmpty();

        // a clean close (page closed) ends the connection at once
        String freshId = transports.connections().stream().filter(x -> x.instanceId().equals("mock-02"))
                .findFirst().orElseThrow().connectionId();
        fresh.socket.sendClose(1000, "client").join();
        waitFor(() -> !transports.isOpen(freshId));
        assertThat(transports.suspended(freshId)).isEmpty();
    }

    static void waitFor(java.util.function.BooleanSupplier ok) throws InterruptedException {
        long end = System.currentTimeMillis() + 5000;
        while (!ok.getAsBoolean()) {
            if (System.currentTimeMillis() > end) {
                throw new AssertionError("condition not reached within 5 s");
            }
            Thread.sleep(25);
        }
    }

    @Test
    void anotherConsoleHoldingTheLeaseBlocksRebinding() throws Exception {
        Console c = login();
        String cookie = openSession(c, "mock-02");
        clock.advance(Duration.ofSeconds(61));
        sweeper.sweepNow();
        Console other = login();
        assertThat(consolePost(other, "/__mmab/api/v1/instances/mock-02/leases", "").statusCode()).isEqualTo(201);
        assertThat(instanceGet("mock-02", "/", cookie, null).statusCode()).as("occupied: no rebinding, told why")
                .isEqualTo(409);
        assertThat(instanceGet("mock-02", "/__mmab/bridge/status", cookie, "same-origin").body()).contains("LEASE_OCCUPIED");
    }

    // =================================================================== 6. backpressure / slow client

    @Test
    void slowClientIsDisconnectedWithoutHurtingOtherSessions() throws Exception {
        Console c = login();
        String slowCookie = openSession(c, "mock-01");
        Ws slow = ws("mock-01", slowCookie, origin("mock-01"), false); // never reads
        Ws fast = ws("mock-02", openSession(c, "mock-02"), origin("mock-02"), true);

        // 64 x 512 KiB = 32 MiB of events towards a client that reads nothing
        slow.request("mock.burst", "burst", "{\"$t\":\"object\",\"v\":{\"count\":64,\"size\":524288}}");
        for (int i = 0; i < 5; i++) {
            long t0 = System.nanoTime();
            fast.request("mock.echo", "f" + i, String.valueOf(i));
            assertThat(fast.response("f" + i).get("error")).isNull();
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)).as("fast session latency")
                    .isLessThan(2_000);
        }
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline && transports.connections().stream()
                .anyMatch(x -> x.instanceId().equals("mock-01"))) {
            Thread.sleep(100);
        }
        assertThat(transports.connections()).noneMatch(x -> x.instanceId().equals("mock-01"));
        assertThat(audit.recent(500)).anySatisfy(e -> {
            assertThat(e.action()).isEqualTo("transport.close");
            assertThat(e.instance()).isEqualTo("mock-01");
            assertThat(e.result()).isEqualTo("QUEUE_LIMIT");
            // 诊断：只有大小与计数，没有内容
            assertThat(e.detail()).contains("cause=OVERFLOW").contains("queuedBytes=").doesNotContain("xxxx");
        });
        // 慢客户端被断开后：适配器与实例仍可用，同一会话重新连接即可
        Ws again = ws("mock-01", slowCookie, origin("mock-01"), true);
        again.request("mock.echo", "x", "\"ok\"");
        assertThat(again.response("x").get("error")).isNull();
        fast.request("mock.echo", "y", "1");
        assertThat(fast.response("y").get("error")).isNull();
    }

    // =================================================================== helpers

    static String consoleOrigin() {
        return "https://127.0.0.1:" + listeners.consolePort();
    }

    static String origin(String id) {
        return "https://127.0.0.1:" + listeners.instancePort(id).orElseThrow();
    }

    static URI console(String path) {
        return URI.create(consoleOrigin() + path);
    }

    static URI instance(String id, String path) {
        return URI.create(origin(id) + path);
    }

    Console login() throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(console("/__mmab/api/v1/auth/login"))
                .header("Origin", consoleOrigin()).header("Sec-Fetch-Site", "same-origin")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of("password", password))))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(r.headers().allValues("set-cookie").get(0)).startsWith("__Host-mmab-console=")
                .contains("; Secure").contains("; HttpOnly").contains("; SameSite=Strict").contains("; Path=/")
                .containsPattern("; Max-Age=(6047\\d\\d|604800);"); // 默认 7 天（按剩余期限，秒级取整）
        Console c = new Console(cookieValue(r, "__Host-mmab-console"),
                JSON.readTree(r.body()).get("csrfToken").stringValue());
        consoles.add(c);
        return c;
    }

    static String cookieValue(HttpResponse<?> r, String name) {
        return r.headers().allValues("set-cookie").stream().filter(v -> v.startsWith(name + "="))
                .map(v -> v.substring(name.length() + 1, v.indexOf(';') < 0 ? v.length() : v.indexOf(';')))
                .findFirst().orElseThrow();
    }

    static HttpResponse<String> consolePost(Console c, String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(console(path)).header("Origin", consoleOrigin())
                .header("Sec-Fetch-Site", "same-origin").header("X-MMAB-CSRF", c.csrf())
                .header("Cookie", "__Host-mmab-console=" + c.cookie()).header("Content-Type", "application/json")
                .POST(body.isEmpty() ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    static JsonNode ticket(Console c, String id) throws Exception {
        HttpResponse<String> r = consolePost(c, "/__mmab/api/v1/instances/" + id + "/launch-tickets", "");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        return JSON.readTree(r.body());
    }

    /** 模拟控制台表单 POST 到实例端口（新标签导航）。 */
    static HttpResponse<String> exchange(String id, String ticket, String origin) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(instance(id, "/__mmab/session/exchange"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Sec-Fetch-Site", "same-site").header("Sec-Fetch-Mode", "navigate")
                .POST(HttpRequest.BodyPublishers.ofString("ticket=" + URLEncoder.encode(ticket, StandardCharsets.UTF_8)));
        if (origin != null) {
            b.header("Origin", origin);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static String openSession(Console c, String id) throws Exception {
        HttpResponse<String> r = exchange(id, ticket(c, id).get("ticket").stringValue(), consoleOrigin());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(303);
        return cookieValue(r, "__Host-mmab-i-" + id);
    }

    static HttpResponse<String> instanceGet(String id, String path, String cookie, String fetchSite) throws Exception {
        return rawGet(instance(id, path), cookie == null ? null : "__Host-mmab-i-" + id + "=" + cookie, fetchSite);
    }

    static HttpResponse<String> rawGet(URI uri, String cookieHeader, String fetchSite) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri).GET();
        if (cookieHeader != null) {
            b.header("Cookie", cookieHeader);
        }
        if (fetchSite != null) {
            b.header("Sec-Fetch-Site", fetchSite);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    AuditLog.Event lastAudit(String action) {
        List<AuditLog.Event> all = audit.recent(500).stream().filter(e -> e.action().equals(action)).toList();
        return all.get(all.size() - 1);
    }

    Ws ws(String id, String cookie, String origin, boolean reading) {
        return ws(id, cookie, origin, reading, "");
    }

    Ws ws(String id, String cookie, String origin, boolean reading, String query) {
        Ws listener = new Ws(reading);
        WebSocket.Builder b = http.newWebSocketBuilder().header("Cookie", "__Host-mmab-i-" + id + "=" + cookie);
        if (origin != null) {
            b.header("Origin", origin);
        }
        String uri = origin(id).replace("https://", "wss://") + "/__mmab/bridge/ws" + query;
        listener.socket = b.buildAsync(URI.create(uri), listener).join();
        sockets.add(listener);
        return listener;
    }

    int wsStatus(String id, String cookie, String origin) {
        try {
            ws(id, cookie, origin, true);
            return 101;
        } catch (CompletionException e) {
            if (e.getCause() instanceof WebSocketHandshakeException h) {
                return h.getResponse().statusCode();
            }
            throw e;
        }
    }

    /** JDK WebSocket 客户端（reading=false 时从不读取，模拟慢客户端）。 */
    static final class Ws implements WebSocket.Listener {
        final BlockingQueue<JsonNode> frames = new LinkedBlockingQueue<>();
        final CompletableFuture<Integer> closeCode = new CompletableFuture<>();
        final boolean reading;
        final StringBuilder partial = new StringBuilder();
        volatile String closeReason;
        WebSocket socket;

        Ws(boolean reading) {
            this.reading = reading;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            if (reading) {
                webSocket.request(1);
            }
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                frames.add(JSON.readTree(partial.toString()));
                partial.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closeReason = reason;
            closeCode.complete(statusCode);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            closeCode.complete(-1);
        }

        void send(String text) {
            socket.sendText(text, true).join();
        }

        void request(String op, String cid, String payload) {
            ObjectNode f = JSON.createObjectNode();
            f.put("kind", "REQUEST");
            f.put("op", op);
            f.put("cid", cid);
            f.put("payload", payload);
            send(JSON.writeValueAsString(f));
        }

        JsonNode nextOfKind(String kind) throws InterruptedException {
            long end = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < end) {
                JsonNode f = frames.poll(200, TimeUnit.MILLISECONDS);
                if (f != null && kind.equals(f.path("kind").asString(""))) {
                    return f;
                }
            }
            throw new AssertionError("no " + kind + " frame");
        }

        JsonNode response(String cid) throws InterruptedException {
            long end = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < end) {
                JsonNode f = frames.poll(200, TimeUnit.MILLISECONDS);
                if (f != null && "RESPONSE".equals(f.path("kind").asString("")) && cid.equals(f.path("cid").asString(""))) {
                    return f;
                }
            }
            throw new AssertionError("no RESPONSE for cid " + cid);
        }

        String errorOf(String cid) throws InterruptedException {
            JsonNode r = response(cid);
            assertThat(r.get("error")).as("error for " + cid).isNotNull();
            return r.get("error").get("code").stringValue();
        }

        List<JsonNode> drain() {
            List<JsonNode> out = new ArrayList<>();
            frames.drainTo(out);
            return out;
        }
    }
}
