package io.github.dipinllx.macminiappbridge.instances;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dipinllx.macminiappbridge.TestSupport;
import io.github.dipinllx.macminiappbridge.protocol.Envelope;
import io.github.dipinllx.macminiappbridge.protocol.ErrorCode;
import io.github.dipinllx.macminiappbridge.protocol.MessageKind;
import io.github.dipinllx.macminiappbridge.protocol.codec.StructuredCodec;
import io.github.dipinllx.macminiappbridge.protocol.codec.SvValue;
import io.github.dipinllx.macminiappbridge.protocol.wire.WireAuth;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 平台 ↔ Node mock 适配器进程的真实握手与 fence（设计 §6.2：adapter 也要认证；未认证 loopback 被拒）。
 */
class ProcessAdapterClientTest {

    private static final long GEN = 4;
    private final BlockingQueue<Envelope> received = new LinkedBlockingQueue<>();
    private final BlockingQueue<String> closed = new LinkedBlockingQueue<>();
    private final StructuredCodec codec = new StructuredCodec();
    private ProcessAdapterClient client;

    @BeforeEach
    void start() throws Exception {
        client = ProcessAdapterClient.start(List.of(TestSupport.node(), "adapters/mock/driver/main.mjs"),
                TestSupport.repoRoot(), "mock-01", GEN, "mock/0.1.0", Duration.ofSeconds(15),
                new ProcessAdapterClient.Listener() {
                    @Override
                    public void onEnvelope(ProcessAdapterClient source, Envelope envelope) {
                        received.add(envelope);
                    }

                    @Override
                    public void onClosed(ProcessAdapterClient source, String reason) {
                        closed.add(reason);
                    }
                });
    }

    @AfterEach
    void stop() {
        client.close();
    }

    private Envelope.Builder request(String op, String cid) {
        return Envelope.builder().profileId("mock/0.1.0").instanceId("mock-01").processGeneration(GEN)
                .connectionId("c-test").leaseId("l-1").fencingToken(1).sequence(1).correlationId(cid)
                .messageKind(MessageKind.REQUEST).operation(op).deadline(Instant.now().plusSeconds(30))
                .payload(codec.encode(new SvValue.SvObject(Map.of("text", new SvValue.SvString("hi")))));
    }

    private Envelope fence(String leaseId, long token) {
        return Envelope.builder().profileId("mock/0.1.0").instanceId("mock-01").processGeneration(GEN)
                .leaseId(leaseId).fencingToken(token).messageKind(MessageKind.CONTROL).operation("mmab.fence")
                .deadline(Instant.now().plusSeconds(30)).build();
    }

    private Envelope next() throws InterruptedException {
        Envelope e = received.poll(5, TimeUnit.SECONDS);
        assertThat(e).as("adapter response").isNotNull();
        return e;
    }

    @Test
    void mutualHandshakeThenFenceEnforcedByTheAdapter() throws Exception {
        assertThat(client.isReady()).isTrue();
        assertThat(client.adapterVersion()).isEqualTo("0.1.0");

        client.send(request("mock.echo", "c-test/1").build()).block(Duration.ofSeconds(5));
        assertThat(next().error().code()).as("no lease pushed yet").isEqualTo(ErrorCode.LEASE_REQUIRED);

        assertThat(client.sendControl(fence("l-1", 1))).isTrue();
        client.send(request("mock.echo", "c-test/2").build()).block(Duration.ofSeconds(5));
        Envelope ok = next();
        assertThat(ok.messageKind()).isEqualTo(MessageKind.RESPONSE);
        assertThat(ok.correlationId()).isEqualTo("c-test/2");
        assertThat(ok.error()).isNull();
        assertThat(codec.decode(ok.payload()))
                .isEqualTo(new SvValue.SvObject(Map.of("text", new SvValue.SvString("hi"))));

        client.send(request("mock.echo", "c-test/3").processGeneration(GEN - 1).build()).block(Duration.ofSeconds(5));
        assertThat(next().error().code()).as("old generation").isEqualTo(ErrorCode.STALE_GENERATION);

        client.send(request("mock.echo", "c-test/4").leaseId("l-0").build()).block(Duration.ofSeconds(5));
        assertThat(next().error().code()).as("old lease").isEqualTo(ErrorCode.STALE_GENERATION);

        client.send(request("mock.secret", "c-test/5").build()).block(Duration.ofSeconds(5));
        assertThat(next().error().code()).as("operation the adapter does not implement")
                .isEqualTo(ErrorCode.UNKNOWN_OPERATION);

        assertThat(client.sendControl(fence(Envelope.NO_LEASE, 0))).isTrue();
        client.send(request("mock.echo", "c-test/6").build()).block(Duration.ofSeconds(5));
        assertThat(next().error().code()).as("lease revoked").isEqualTo(ErrorCode.LEASE_REQUIRED);
    }

    @Test
    void unauthenticatedLoopbackConnectionIsRefused() throws Exception {
        try (Socket raw = new Socket(InetAddress.getLoopbackAddress(), client.adapterPort())) {
            raw.setSoTimeout(5_000);
            BufferedReader in = new BufferedReader(new InputStreamReader(raw.getInputStream(), StandardCharsets.UTF_8));
            OutputStream out = raw.getOutputStream();
            assertThat(in.readLine()).contains("\"t\":\"hello\"");
            // A well-formed frame MAC'd with a guessed key, sent without the handshake
            String forged = WireAuth.encodeFrame(new byte[32], WireAuth.PLATFORM_TO_ADAPTER, 1,
                    request("mock.echo", "c-x/1").build());
            out.write((forged + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            assertThat(in.readLine()).isEqualTo("{\"t\":\"error\",\"code\":\"UNAUTHENTICATED\"}");
            assertThat(in.readLine()).as("connection closed").isNull();
        }
        try (Socket raw = new Socket(InetAddress.getLoopbackAddress(), client.adapterPort())) {
            raw.setSoTimeout(5_000);
            BufferedReader in = new BufferedReader(new InputStreamReader(raw.getInputStream(), StandardCharsets.UTF_8));
            in.readLine();
            String guess = "{\"t\":\"auth\",\"protocol\":\"1.0\",\"nonce\":\"n\",\"mac\":\"" + "0".repeat(64) + "\"}";
            raw.getOutputStream().write((guess + "\n").getBytes(StandardCharsets.UTF_8));
            assertThat(in.readLine()).isEqualTo("{\"t\":\"error\",\"code\":\"UNAUTHENTICATED\"}");
        }
        assertThat(client.isReady()).as("the authenticated platform connection is unaffected").isTrue();
    }

    @Test
    void closingTheClientStopsTheAdapterProcess() throws Exception {
        int port = client.adapterPort();
        client.close();
        assertThat(closed.poll(5, TimeUnit.SECONDS)).isNotNull();
        assertThat(client.isReady()).isFalse();
        Thread.sleep(300);
        boolean refused;
        try (Socket ignored = new Socket(InetAddress.getLoopbackAddress(), port)) {
            refused = false;
        } catch (java.io.IOException e) {
            refused = true;
        }
        assertThat(refused).as("adapter listener gone").isTrue();
    }
}
