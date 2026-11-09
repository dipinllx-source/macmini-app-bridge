package io.github.dipinllx.macminiappbridge.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dipinllx.macminiappbridge.MacminiAppBridgeApplication;
import io.github.dipinllx.macminiappbridge.TestSupport;
import io.github.dipinllx.macminiappbridge.identity.PasswordHasher;
import io.github.dipinllx.macminiappbridge.identity.Tokens;
import io.github.dipinllx.macminiappbridge.instances.InstanceRegistry;
import io.github.dipinllx.macminiappbridge.instances.InstanceRuntime;
import io.github.dipinllx.macminiappbridge.leases.LeaseManager;
import io.github.dipinllx.macminiappbridge.protocol.ErrorCode;
import io.github.dipinllx.macminiappbridge.proxy.BridgeConnection;
import io.github.dipinllx.macminiappbridge.proxy.TransportRegistry;
import io.github.dipinllx.macminiappbridge.web.ListenerRegistry;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * M3 平台通用件的端到端离线测试（ADR-0005）：多地址精确绑定（127.0.0.1 + ::1 代替 LAN IP + 127.0.0.1）、
 * Host 与到达地址一致、同步调用端点、MessagePort 中继（PortTable）、适配器要求关闭连接、验证授权的约束。
 * 适配器为 Node mock（不连接任何真实应用）；浏览器用 JDK 客户端按浏览器头部模拟。
 */
class M3PlatformEndToEndTest {

    static final JsonMapper JSON = new JsonMapper();
    static ConfigurableApplicationContext ctx;
    static HttpClient http;
    static String password;
    static ListenerRegistry listeners;
    static LeaseManager leases;
    static InstanceRegistry instances;
    static TransportRegistry transports;
    static io.github.dipinllx.macminiappbridge.audit.AuditLog audit;

    record Console(String cookie, String csrf, String origin) {
    }

    final List<OfflineEndToEndTest.Ws> sockets = new ArrayList<>();

    @BeforeAll
    static void boot() throws Exception {
        TestTls tls = TestTls.create("ip:127.0.0.1,ip:::1");
        password = Tokens.random();
        String hash = PasswordHasher.hash(password.toCharArray(), PasswordHasher.MIN_ITERATIONS);
        List<String> args = new ArrayList<>(List.of(
                "--mmab.home=" + TestSupport.repoRoot(),
                "--mmab.data-dir=" + java.nio.file.Files.createTempDirectory("mmab-e2e-data"),
                "--mmab.console.port=0",
                "--mmab.console.bind-addresses[0]=127.0.0.1",
                "--mmab.console.bind-addresses[1]=::1",
                "--mmab.web.tls-bundle=mmabtest",
                "--spring.ssl.bundle.jks.mmabtest.keystore.location=file:" + tls.keystore,
                "--spring.ssl.bundle.jks.mmabtest.keystore.password=" + tls.storePassword,
                "--spring.ssl.bundle.jks.mmabtest.keystore.type=PKCS12",
                "--mmab.operator.password-hash=" + hash,
                "--mmab.testing.allow-test-adapters=true",
                "--mmab.timeouts.request-deadline=3s",
                "--mmab.adapters[0].app-id=mock",
                "--mmab.adapters[0].descriptor=adapters/mock/adapter.yaml",
                "--mmab.adapters[0].command[0]=" + TestSupport.node(),
                "--mmab.adapters[0].command[1]=adapters/mock/driver/main.mjs",
                "--mmab.adapters[0].resources=adapters/mock/fixture",
                "--mmab.instances[0].id=mock-01", "--mmab.instances[0].app-id=mock",
                "--mmab.instances[0].display-name=Mock", "--mmab.instances[0].port=0",
                "--mmab.instances[0].strategy=test-mock", "--mmab.instances[0].enabled=true",
                // 验证授权只能用于"声明了 evidence 且该能力缺 live 证据"的适配器；mock 未声明 evidence → 实例阻塞
                "--mmab.instances[1].id=mock-03", "--mmab.instances[1].app-id=mock",
                "--mmab.instances[1].display-name=Mock grant", "--mmab.instances[1].port=0",
                "--mmab.instances[1].strategy=test-mock", "--mmab.instances[1].enabled=true",
                "--mmab.instances[1].verify-capabilities[0]=ui.render"));
        ctx = new SpringApplicationBuilder(MacminiAppBridgeApplication.class).run(args.toArray(String[]::new));
        listeners = ctx.getBean(ListenerRegistry.class);
        leases = ctx.getBean(LeaseManager.class);
        instances = ctx.getBean(InstanceRegistry.class);
        transports = ctx.getBean(TransportRegistry.class);
        audit = ctx.getBean(io.github.dipinllx.macminiappbridge.audit.AuditLog.class);
        http = HttpClient.newBuilder().sslContext(tls.clientContext).version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build();
        assertThat(instances.require("mock-01").ready()).isTrue();
    }

    @AfterAll
    static void shutdown() {
        if (ctx != null) {
            ctx.close();
        }
    }

    @AfterEach
    void cleanup() {
        for (OfflineEndToEndTest.Ws w : sockets) {
            w.socket.abort();
        }
        leases.revokeInstance("mock-01", ErrorCode.LEASE_REQUIRED);
    }

    // =================================================================== multi-address bind (design §5.1)

    @Test
    void everyListenerBindsEveryExactAddressAndHostMustMatchTheArrivalAddress() throws Exception {
        int console = listeners.consolePort();
        int inst = listeners.instancePort("mock-01").orElseThrow();
        assertThat(listeners.hosts()).containsExactly("127.0.0.1", "[::1]");
        for (String host : List.of("127.0.0.1", "[::1]")) {
            assertThat(get(URI.create("https://" + host + ":" + console + "/__mmab/api/v1/health"), null).statusCode())
                    .as("console on " + host).isEqualTo(200);
            assertThat(get(URI.create("https://" + host + ":" + inst + "/"), null).statusCode())
                    .as("instance on " + host + " answers (login hint)").isEqualTo(401);
        }
        // 从 127.0.0.1 进来的连接不能自称 ::1，反之亦然（Host 必须对应到达的本地地址）
        assertThat(get(URI.create("https://127.0.0.1:" + console + "/__mmab/api/v1/health"), "[::1]:" + console)
                .statusCode()).isEqualTo(421);
        assertThat(get(URI.create("https://[::1]:" + inst + "/"), "127.0.0.1:" + inst).statusCode()).isEqualTo(421);
        // 只监听登记地址：不在 public-hosts 里的名字一律 421
        assertThat(get(URI.create("https://127.0.0.1:" + console + "/__mmab/api/v1/health"), "localhost:" + console)
                .statusCode()).isEqualTo(421);

        // 从 [::1] 控制台开窗：票据目标 Origin 跟随控制台所在地址
        Console c = login("[::1]");
        JsonNode ticket = ticket(c, "mock-01");
        String target = "https://[::1]:" + inst;
        assertThat(ticket.get("exchangeUrl").stringValue()).isEqualTo(target + "/__mmab/session/exchange");
        HttpResponse<String> ex = exchange(target, ticket.get("ticket").stringValue(), c.origin());
        assertThat(ex.statusCode()).isEqualTo(303);
        // 换到另一地址兑换同一张票据：目标 Origin 不符 → 拒绝
        JsonNode t2 = ticket(c, "mock-01");
        assertThat(exchange("https://127.0.0.1:" + inst, t2.get("ticket").stringValue(), c.origin()).statusCode())
                .isEqualTo(403);
    }

    @Test
    void verificationGrantsCannotOpenAdaptersWithoutEvidenceDeclaration() {
        InstanceRuntime rt = instances.require("mock-03");
        assertThat(rt.state()).isEqualTo(InstanceRuntime.State.BLOCKED);
        assertThat(rt.blockedReason()).isEqualTo(ErrorCode.COMPATIBILITY_BLOCKED);
    }

    // =================================================================== sync endpoint

    @Test
    void syncCallEndpointIsGatedLikeTheWebSocket() throws Exception {
        Console c = login("127.0.0.1");
        String cookie = openSession(c, "127.0.0.1");
        String origin = instanceOrigin("127.0.0.1");

        JsonNode ok = JSON.readTree(syncCall(cookie, origin, "same-origin", "application/json",
                frame("mock.syncEcho", "\"hi\"")).body());
        assertThat(ok.get("payload").stringValue()).isEqualTo("{\"$t\":\"object\",\"v\":{\"sync\":\"hi\"}}");

        assertThat(errorOf(syncCall(cookie, origin, "same-origin", "application/json", frame("mock.echo", "1"))))
                .as("not declared sync").isEqualTo("UNKNOWN_OPERATION");
        assertThat(errorOf(syncCall(cookie, origin, "same-origin", "application/json", frame("mock.compose", "1"))))
                .isEqualTo("CAPABILITY_DISABLED");
        assertThat(errorOf(syncCall(cookie, origin, "same-origin", "application/json",
                frame("mock.syncEcho", "{\"$t\":\"port\",\"id\":\"w1\"}"))))
                .as("no ports through the sync endpoint").isEqualTo("CODEC_UNSUPPORTED");
        assertThat(errorOf(syncCall(cookie, origin, "same-origin", "application/json", "{\"op\":\"mock.syncEcho\"}")))
                .isEqualTo("MALFORMED_FRAME");
        assertThat(errorOf(syncCall(cookie, origin, "same-origin", "application/json",
                "{\"op\":\"mock.syncEcho\",\"payload\":\"1\",\"extra\":1}"))).isEqualTo("MALFORMED_FRAME");

        assertThat(syncCall(cookie, null, "same-origin", "application/json", frame("mock.syncEcho", "1")).statusCode())
                .as("missing Origin").isEqualTo(403);
        assertThat(syncCall(cookie, "null", "same-origin", "application/json", frame("mock.syncEcho", "1"))
                .statusCode()).isEqualTo(403);
        assertThat(syncCall(cookie, "https://127.0.0.1:" + listeners.consolePort(), "same-site", "application/json",
                frame("mock.syncEcho", "1")).statusCode()).as("other port's Origin").isEqualTo(403);
        assertThat(syncCall(cookie, origin, "cross-site", "application/json", frame("mock.syncEcho", "1"))
                .statusCode()).isEqualTo(403);
        assertThat(syncCall(cookie, origin, "same-origin", "text/plain", frame("mock.syncEcho", "1")).statusCode())
                .isEqualTo(415);
        assertThat(syncCall(null, origin, "same-origin", "application/json", frame("mock.syncEcho", "1"))
                .statusCode()).isEqualTo(401);
        assertThat(get(URI.create(origin + "/__mmab/bridge/call"), null, "__Host-mmab-i-mock-01=" + cookie)
                .statusCode()).isEqualTo(405);

        // reliable-personal-entry：租约撤销后会话保留并重新绑定新租约（同一父控制台会话）；注销后才失效
        long fenceBefore = leases.current("mock-01").orElseThrow().fencingToken();
        leases.revokeInstance("mock-01", ErrorCode.LEASE_REQUIRED);
        assertThat(syncCall(cookie, origin, "same-origin", "application/json", frame("mock.syncEcho", "1"))
                .statusCode()).isEqualTo(200);
        assertThat(leases.current("mock-01").orElseThrow().fencingToken()).isGreaterThan(fenceBefore);
    }

    // =================================================================== browser diagnostics (reliable-personal-entry D1)

    @Test
    void browserDiagnosticsAcceptOnlyWhitelistedMetadataFromTheInstancePage() throws Exception {
        Console c = login("127.0.0.1");
        String cookie = openSession(c, "127.0.0.1");
        String origin = instanceOrigin("127.0.0.1");
        String body = "{\"e\":\"close\",\"code\":1006,\"clean\":false,\"unl\":false,\"vis\":\"visible\","
                + "\"up\":300123,\"ws\":299000,\"recent\":[\"host:thread-updated\",\"view:ready\"]}";
        assertThat(diag(cookie, origin, "same-origin", "text/plain;charset=UTF-8", body).statusCode()).isEqualTo(204);
        assertThat(audit.recent(50)).anySatisfy(e -> {
            assertThat(e.action()).isEqualTo("browser.diag");
            assertThat(e.result()).isEqualTo("close");
            assertThat(e.detail()).contains("code=1006").contains("up=300123")
                    .contains("recent=host:thread-updated,view:ready");
        });
        String prev = "{\"e\":\"load\",\"nav\":\"reload\",\"prev\":{\"e\":\"unload\",\"up\":5}}";
        assertThat(diag(cookie, origin, "same-origin", "text/plain", prev).statusCode()).isEqualTo(204);

        // anything outside the whitelist is refused: free text, unknown keys, bad enums, content-looking names
        for (String bad : new String[] {
                "{\"e\":\"close\",\"msg\":\"hello\"}",
                "{\"e\":\"close\",\"recent\":[\"has space\"]}",
                "{\"e\":\"crash\"}",
                "{\"e\":\"close\",\"code\":70000}",
                "{\"e\":\"close\",\"reason\":\"lower case\"}",
                "{\"e\":\"load\",\"prev\":{\"e\":\"unload\",\"nav\":\"reload\"}}",
                "not json"}) {
            assertThat(diag(cookie, origin, "same-origin", "text/plain", bad).statusCode()).as(bad).isEqualTo(400);
        }
        assertThat(audit.recent(200)).noneSatisfy(e -> assertThat(e.detail()).contains("hello"));
        assertThat(diag(cookie, null, "same-origin", "text/plain", body).statusCode()).as("missing Origin").isEqualTo(403);
        assertThat(diag(cookie, "https://127.0.0.1:" + listeners.consolePort(), "same-site", "text/plain", body)
                .statusCode()).as("other port").isEqualTo(403);
        assertThat(diag(null, origin, "same-origin", "text/plain", body).statusCode()).isEqualTo(401);
        assertThat(diag(cookie, origin, "same-origin", "application/x-www-form-urlencoded", body).statusCode())
                .isEqualTo(415);
        assertThat(diag(cookie, origin, "same-origin", "text/plain", "{\"e\":\"close\",\"recent\":[\""
                + "a".repeat(5000) + "\"]}").statusCode()).isEqualTo(413);
    }

    static HttpResponse<String> diag(String cookie, String origin, String fetchSite, String type, String body)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(instanceOrigin("127.0.0.1") + "/__mmab/bridge/diag"))
                .header("Content-Type", type).header("Sec-Fetch-Site", fetchSite)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (cookie != null) {
            b.header("Cookie", "__Host-mmab-i-mock-01=" + cookie);
        }
        if (origin != null) {
            b.header("Origin", origin);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    // =================================================================== MessagePort relay (design §9.4)

    @Test
    void messagePortsRelayInBothDirectionsWithIdSpacesNestingAndClose() throws Exception {
        Console c = login("127.0.0.1");
        String cookie = openSession(c, "127.0.0.1");
        OfflineEndToEndTest.Ws ws = ws(cookie);
        onlyConnection();

        ws.request("mock.openPort", "1", "{\"$t\":\"undef\"}");
        assertThat(ws.response("1").get("payload").stringValue())
                .isEqualTo("{\"$t\":\"object\",\"v\":{\"port\":{\"$t\":\"port\",\"id\":\"n1\"}}}");
        ws.send(portData("n1", "\"x\""));
        JsonNode echo = ws.nextOfKind("PORT_DATA");
        assertThat(echo.get("port").stringValue()).isEqualTo("n1");
        assertThat(echo.get("payload").stringValue()).isEqualTo("{\"$t\":\"object\",\"v\":{\"echo\":\"x\"}}");

        // 原生侧经已有端口再转移一个新端口（嵌套，深度 2）
        ws.send(portData("n1", "{\"$t\":\"object\",\"v\":{\"give\":true}}"));
        assertThat(ws.nextOfKind("PORT_DATA").get("payload").stringValue()).contains("\"id\":\"n2\"");
        BridgeConnection conn = onlyConnection();
        assertThat(conn.ports().get("n2").depth()).isEqualTo(2);

        // 浏览器把自己的端口转给适配器（REQUEST 内）与经端口嵌套转移（PORT_DATA 内）
        ws.request("mock.adoptPort", "2", "{\"$t\":\"object\",\"v\":{\"port\":{\"$t\":\"port\",\"id\":\"w1\"}}}");
        JsonNode hello = ws.nextOfKind("PORT_DATA");
        assertThat(hello.get("port").stringValue()).isEqualTo("w1");
        ws.send(portData("n1", "{\"$t\":\"object\",\"v\":{\"reply\":{\"$t\":\"port\",\"id\":\"w2\"}}}"));
        JsonNode nested = ws.nextOfKind("PORT_DATA");
        assertThat(nested.get("port").stringValue()).isEqualTo("w2");
        assertThat(conn.ports().get("w2").depth()).isEqualTo(2);

        // ID 空间：浏览器不能冒用 n*，也不能重复转移已用过的 ID；适配器不能冒用 w*
        ws.request("mock.adoptPort", "3", "{\"$t\":\"object\",\"v\":{\"port\":{\"$t\":\"port\",\"id\":\"n77\"}}}");
        assertThat(ws.errorOf("3")).isEqualTo("PORT_STATE");
        ws.request("mock.adoptPort", "4", "{\"$t\":\"object\",\"v\":{\"port\":{\"$t\":\"port\",\"id\":\"w1\"}}}");
        assertThat(ws.errorOf("4")).isEqualTo("PORT_STATE");
        ws.request("mock.forgePort", "5", "{\"$t\":\"undef\"}");
        assertThat(ws.errorOf("5")).isEqualTo("PORT_STATE");
        assertThat(conn.ports().get("w999")).isNull();

        // 未知端口：连接级错误；已关闭端口的迟到帧：静默丢弃，不会被接到新端口
        ws.send(portData("w55", "1"));
        assertThat(ws.nextOfKind("ERROR").get("error").get("code").stringValue()).isEqualTo("PORT_STATE");
        ws.send("{\"kind\":\"PORT_CLOSE\",\"port\":\"n1\"}");
        ws.send(portData("n1", "\"late\""));
        // 原生侧关闭端口 → 浏览器收到 PORT_CLOSE；在它之前不得出现 n1 的任何回帧
        ws.send(portData("n2", "\"close\""));
        List<JsonNode> seen = new ArrayList<>();
        long end = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < end) {
            JsonNode f = ws.frames.poll(200, TimeUnit.MILLISECONDS);
            if (f == null) {
                continue;
            }
            seen.add(f);
            if ("PORT_CLOSE".equals(f.path("kind").asString(""))) {
                break;
            }
        }
        assertThat(seen).last().satisfies(f -> assertThat(f.get("port").stringValue()).isEqualTo("n2"));
        assertThat(seen).noneMatch(f -> f.path("port").asString("").equals("n1"));
        assertThat(conn.ports().get("n1").state()).isEqualTo(io.github.dipinllx.macminiappbridge.protocol.ports.PortTable.State.CLOSED);

        // 浏览器连接关闭 → 适配器收到 CONTROL mmab.close 并释放原生侧状态
        String closedId = conn.connectionId();
        ws.socket.sendClose(1000, "bye").join();
        waitUntil(() -> transports.connections().isEmpty());
        OfflineEndToEndTest.Ws again = ws(cookie);
        again.request("mock.stats", "s", "{\"$t\":\"undef\"}");
        String stats = again.response("s").get("payload").stringValue();
        assertThat(stats).contains(closedId);
        assertThat(conn.ports().openCount()).isZero();
    }

    @Test
    void portBudgetsAreEnforced() throws Exception {
        Console c = login("127.0.0.1");
        String cookie = openSession(c, "127.0.0.1");
        OfflineEndToEndTest.Ws ws = ws(cookie);
        ws.request("mock.openPort", "root", "{\"$t\":\"undef\"}");
        ws.response("root");
        // 嵌套深度上限 32：第 33 层端口让连接以 QUEUE_LIMIT 关闭
        String current = "n1";
        for (int depth = 2; depth <= 32; depth++) {
            ws.send(portData(current, "{\"$t\":\"object\",\"v\":{\"give\":true}}"));
            JsonNode f = ws.nextOfKind("PORT_DATA");
            current = f.get("payload").stringValue().replaceAll(".*\"id\":\"(n\\d+)\".*", "$1");
        }
        assertThat(onlyConnection().ports().get(current).depth()).isEqualTo(32);
        ws.send(portData(current, "{\"$t\":\"object\",\"v\":{\"give\":true}}"));
        assertThat(ws.closeCode.get(10, TimeUnit.SECONDS)).isEqualTo(BridgeConnection.CLOSE_POLICY);
        assertThat(ws.closeReason).isEqualTo("QUEUE_LIMIT");

        // 每连接端口数上限 256：超出后的端口引用被拒，连接内仍只有 256 个打开的端口
        OfflineEndToEndTest.Ws ws2 = ws(cookie);
        BridgeConnection second = onlyConnection();
        for (int i = 1; i <= 256; i++) {
            ws2.request("mock.openPort", "p" + i, "{\"$t\":\"undef\"}");
            assertThat(ws2.response("p" + i).get("error")).isNull();
        }
        ws2.request("mock.openPort", "p257", "{\"$t\":\"undef\"}");
        assertThat(ws2.errorOf("p257")).isEqualTo("PORT_STATE");
        assertThat(second.ports().openCount()).isEqualTo(256);
    }

    @Test
    void adapterCanAskThePlatformToCloseABrowserConnection() throws Exception {
        Console c = login("127.0.0.1");
        OfflineEndToEndTest.Ws ws = ws(openSession(c, "127.0.0.1"));
        ws.request("mock.closeMe", "1", "{\"$t\":\"undef\"}");
        assertThat(ws.closeCode.get(10, TimeUnit.SECONDS)).isEqualTo(BridgeConnection.CLOSE_POLICY);
        assertThat(ws.closeReason).isEqualTo("QUEUE_LIMIT");
        assertThat(instances.require("mock-01").ready()).as("other connections / adapter unaffected").isTrue();
    }

    // =================================================================== helpers

    interface Check {
        boolean ok();
    }

    static void waitUntil(Check c) throws InterruptedException {
        long end = System.currentTimeMillis() + 10_000;
        while (!c.ok() && System.currentTimeMillis() < end) {
            Thread.sleep(50);
        }
        assertThat(c.ok()).isTrue();
    }

    static String portData(String port, String payload) {
        ObjectNode f = JSON.createObjectNode();
        f.put("kind", "PORT_DATA");
        f.put("port", port);
        f.put("payload", payload);
        return JSON.writeValueAsString(f);
    }

    static String frame(String op, String payload) {
        return JSON.writeValueAsString(Map.of("op", op, "payload", payload));
    }

    static String errorOf(HttpResponse<String> r) {
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return JSON.readTree(r.body()).get("error").get("code").stringValue();
    }

    static String instanceOrigin(String host) {
        return "https://" + host + ":" + listeners.instancePort("mock-01").orElseThrow();
    }

    static HttpResponse<String> get(URI uri, String host) throws Exception {
        return get(uri, host, null);
    }

    static HttpResponse<String> get(URI uri, String host, String cookie) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri).GET().header("Sec-Fetch-Site", "same-origin");
        if (host != null) {
            b.header("Host", host);
        }
        if (cookie != null) {
            b.header("Cookie", cookie);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static HttpResponse<String> syncCall(String cookie, String origin, String fetchSite, String type, String body)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(instanceOrigin("127.0.0.1") + "/__mmab/bridge/call"))
                .header("Content-Type", type).header("Sec-Fetch-Site", fetchSite)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (cookie != null) {
            b.header("Cookie", "__Host-mmab-i-mock-01=" + cookie);
        }
        if (origin != null) {
            b.header("Origin", origin);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    Console login(String host) throws Exception {
        String origin = "https://" + host + ":" + listeners.consolePort();
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(origin + "/__mmab/api/v1/auth/login"))
                .header("Origin", origin).header("Sec-Fetch-Site", "same-origin")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of("password", password))))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return new Console(OfflineEndToEndTest.cookieValue(r, "__Host-mmab-console"),
                JSON.readTree(r.body()).get("csrfToken").stringValue(), origin);
    }

    static JsonNode ticket(Console c, String id) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(
                        URI.create(c.origin() + "/__mmab/api/v1/instances/" + id + "/launch-tickets"))
                .header("Origin", c.origin()).header("Sec-Fetch-Site", "same-origin").header("X-MMAB-CSRF", c.csrf())
                .header("Cookie", "__Host-mmab-console=" + c.cookie()).POST(HttpRequest.BodyPublishers.noBody())
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        return JSON.readTree(r.body());
    }

    static HttpResponse<String> exchange(String targetOrigin, String ticket, String origin) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(targetOrigin + "/__mmab/session/exchange"))
                .header("Content-Type", "application/x-www-form-urlencoded").header("Origin", origin)
                .header("Sec-Fetch-Site", "same-site").header("Sec-Fetch-Mode", "navigate")
                .POST(HttpRequest.BodyPublishers.ofString("ticket=" + URLEncoder.encode(ticket, StandardCharsets.UTF_8)))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    String openSession(Console c, String host) throws Exception {
        HttpResponse<String> r = exchange(instanceOrigin(host), ticket(c, "mock-01").get("ticket").stringValue(),
                c.origin());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(303);
        return OfflineEndToEndTest.cookieValue(r, "__Host-mmab-i-mock-01");
    }

    OfflineEndToEndTest.Ws ws(String cookie) {
        OfflineEndToEndTest.Ws listener = new OfflineEndToEndTest.Ws(true);
        String origin = instanceOrigin("127.0.0.1");
        listener.socket = http.newWebSocketBuilder().header("Cookie", "__Host-mmab-i-mock-01=" + cookie)
                .header("Origin", origin)
                .buildAsync(URI.create(origin.replace("https://", "wss://") + "/__mmab/bridge/ws"), listener).join();
        sockets.add(listener);
        return listener;
    }

    /** 等到只剩一条活动连接（上一个用例的连接已随租约撤销关闭）并返回它。 */
    static BridgeConnection onlyConnection() throws InterruptedException {
        waitUntil(() -> transports.connections().size() == 1);
        return transports.connections().get(0);
    }
}
