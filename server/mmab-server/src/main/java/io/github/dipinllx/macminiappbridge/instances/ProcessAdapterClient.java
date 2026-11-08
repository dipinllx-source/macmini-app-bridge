package io.github.dipinllx.macminiappbridge.instances;

import io.github.dipinllx.macminiappbridge.protocol.AdapterProtocol;
import io.github.dipinllx.macminiappbridge.protocol.Envelope;
import io.github.dipinllx.macminiappbridge.protocol.ErrorCode;
import io.github.dipinllx.macminiappbridge.protocol.ProtocolException;
import io.github.dipinllx.macminiappbridge.protocol.ProtocolVersion;
import io.github.dipinllx.macminiappbridge.protocol.StrictJson;
import io.github.dipinllx.macminiappbridge.protocol.wire.WireAuth;
import io.github.dipinllx.macminiappbridge.proxy.BoundedFrameQueue;
import io.github.dipinllx.macminiappbridge.proxy.BoundedFrameQueue.Lane;
import io.github.dipinllx.macminiappbridge.proxy.BoundedFrameQueue.Offer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 平台侧的适配器进程客户端（设计 §6.2、§10.3、§14.5 {@code AdapterClient}）。
 *
 * <ol>
 *   <li>按静态配置的命令启动适配器进程（不接受 API 输入）；32 字节服务密钥经 stdin 传入后立即关闭 stdin。</li>
 *   <li>适配器只监听 127.0.0.1，stdout 首行报告端口；平台连接后完成双向 HMAC 握手（{@link WireAuth}）。</li>
 *   <li>此后单写线程按 {@link BoundedFrameQueue} 顺序发送（控制通道优先），单读线程校验 MAC 与序号。</li>
 * </ol>
 */
public final class ProcessAdapterClient implements AutoCloseable {

    /** 来自适配器的帧与连接关闭回调（在读线程上调用，实现必须非阻塞）。 */
    public interface Listener {
        void onEnvelope(ProcessAdapterClient source, Envelope envelope);

        void onClosed(ProcessAdapterClient source, String reason);
    }

    private static final Logger log = LoggerFactory.getLogger(ProcessAdapterClient.class);

    private final String instanceId;
    private final long generation;
    private final String profileId;
    private final Listener listener;
    private final BoundedFrameQueue<Envelope> outQueue = new BoundedFrameQueue<>(BoundedFrameQueue.Budget.inbound());
    private final AtomicBoolean closed = new AtomicBoolean();
    private Process process;
    private Socket socket;
    private byte[] sessionKey;
    private long outCounter;
    private long inCounter;
    private int adapterPort;
    private String adapterVersion = "unknown";
    private volatile boolean ready;

    private ProcessAdapterClient(String instanceId, long generation, String profileId, Listener listener) {
        this.instanceId = instanceId;
        this.generation = generation;
        this.profileId = profileId;
        this.listener = listener;
    }

    /** 启动适配器进程并完成认证握手；失败时进程被终止并抛出异常。 */
    public static ProcessAdapterClient start(List<String> command, Path workDir, String instanceId, long generation,
            String profileId, Duration startupTimeout, Listener listener) throws IOException {
        ProcessAdapterClient c = new ProcessAdapterClient(instanceId, generation, profileId, listener);
        try {
            c.launch(command, workDir, startupTimeout);
            return c;
        } catch (IOException | RuntimeException e) {
            c.shutdown("startup failed");
            throw e;
        }
    }

    private void launch(List<String> command, Path workDir, Duration startupTimeout) throws IOException {
        if (command.isEmpty()) {
            throw new IOException("adapter command not configured");
        }
        byte[] serviceKey = WireAuth.newServiceKey();
        ProcessBuilder pb = new ProcessBuilder(command).directory(workDir.toFile());
        pb.environment().keySet().removeIf(k -> k.startsWith("MMAB_"));
        process = pb.start();
        Thread.ofVirtual().name("mmab-adapter-stderr-" + instanceId).start(() -> pump(process.getErrorStream(), "stderr"));

        ObjectNode boot = StrictJson.WIRE.object();
        boot.put("mmabAdapterBootstrap", 1);
        boot.put("serviceKey", Base64.getEncoder().encodeToString(serviceKey));
        boot.put("instanceId", instanceId);
        boot.put("generation", generation);
        boot.put("profileId", profileId);
        boot.put("protocol", AdapterProtocol.CURRENT.toString());
        try (OutputStream stdin = process.getOutputStream()) {
            stdin.write((StrictJson.WIRE.write(boot) + "\n").getBytes(StandardCharsets.UTF_8));
        }

        BoundedLineReader stdout = new BoundedLineReader(process.getInputStream());
        CompletableFuture<String> readyLine = CompletableFuture.supplyAsync(() -> {
            try {
                return stdout.readLine(AdapterProtocol.MAX_HANDSHAKE_LINE_BYTES);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }, r -> Thread.ofVirtual().start(r));
        String line;
        try {
            line = readyLine.get(startupTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted waiting for adapter", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IOException("adapter did not report ready in time", e);
        }
        if (line == null) {
            throw new IOException("adapter exited before ready");
        }
        JsonNode announced = StrictJson.WIRE.parse(line);
        if (announced.get("mmabAdapterReady") == null || announced.get("port") == null
                || !announced.get("port").isIntegralNumber()) {
            throw new IOException("unexpected adapter ready line");
        }
        adapterPort = announced.get("port").asInt();
        if (adapterPort <= 0 || adapterPort > 65535) {
            throw new IOException("invalid adapter port");
        }
        if (announced.get("adapterVersion") != null && announced.get("adapterVersion").isString()) {
            adapterVersion = announced.get("adapterVersion").stringValue();
        }
        Thread.ofVirtual().name("mmab-adapter-stdout-" + instanceId).start(() -> pumpReader(stdout));
        process.onExit().thenRun(() -> shutdown("adapter process exited"));

        socket = new Socket();
        socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), adapterPort), 3_000);
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(AdapterProtocol.HANDSHAKE_TIMEOUT_MILLIS);
        BoundedLineReader in = new BoundedLineReader(socket.getInputStream());
        OutputStream out = socket.getOutputStream();
        handshake(serviceKey, in, out);
        Arrays.fill(serviceKey, (byte) 0);
        socket.setSoTimeout(0);

        Thread.ofVirtual().name("mmab-adapter-reader-" + instanceId).start(() -> readLoop(in));
        Thread.ofVirtual().name("mmab-adapter-writer-" + instanceId).start(() -> writeLoop(out));
        ready = true;
        log.info("adapter ready instance={} generation={} adapterVersion={}", instanceId, generation, adapterVersion);
    }

    private void handshake(byte[] serviceKey, BoundedLineReader in, OutputStream out) throws IOException {
        JsonNode hello = StrictJson.WIRE.parse(requireLine(in));
        if (!"hello".equals(text(hello, "t")) || hello.get("protocol") == null) {
            throw new ProtocolException(ErrorCode.UNAUTHENTICATED, "expected hello");
        }
        ProtocolVersion min = ProtocolVersion.parse(text(hello.get("protocol"), "min"));
        ProtocolVersion max = ProtocolVersion.parse(text(hello.get("protocol"), "max"));
        if (min.compareTo(AdapterProtocol.CURRENT) > 0 || AdapterProtocol.CURRENT.compareTo(max) > 0) {
            writeLine(out, "{\"t\":\"error\",\"code\":\"PROTOCOL_VERSION_UNSUPPORTED\"}");
            throw new ProtocolException(ErrorCode.PROTOCOL_VERSION_UNSUPPORTED, "adapter protocol range");
        }
        if (!instanceId.equals(text(hello, "instanceId"))) {
            throw new ProtocolException(ErrorCode.UNAUTHENTICATED, "instance mismatch");
        }
        String adapterNonce = text(hello, "nonce");
        String clientNonce = WireAuth.newNonce();
        String protocol = AdapterProtocol.CURRENT.toString();
        ObjectNode auth = StrictJson.WIRE.object();
        auth.put("t", "auth");
        auth.put("protocol", protocol);
        auth.put("nonce", clientNonce);
        auth.put("mac", WireAuth.platformProof(serviceKey, adapterNonce, clientNonce, instanceId, generation, protocol));
        writeLine(out, StrictJson.WIRE.write(auth));
        JsonNode ok = StrictJson.WIRE.parse(requireLine(in));
        if (!"auth-ok".equals(text(ok, "t")) || !WireAuth.constantTimeEquals(text(ok, "mac"),
                WireAuth.adapterProof(serviceKey, adapterNonce, clientNonce, instanceId, generation, protocol))) {
            throw new ProtocolException(ErrorCode.UNAUTHENTICATED, "adapter proof rejected");
        }
        sessionKey = WireAuth.sessionKey(serviceKey, adapterNonce, clientNonce);
    }

    private static String requireLine(BoundedLineReader in) throws IOException {
        String line = in.readLine(AdapterProtocol.MAX_HANDSHAKE_LINE_BYTES);
        if (line == null) {
            throw new ProtocolException(ErrorCode.UNAUTHENTICATED, "connection closed during handshake");
        }
        return line;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n == null ? null : n.get(field);
        if (v == null || !v.isString()) {
            throw new ProtocolException(ErrorCode.MALFORMED_FRAME, "missing " + field);
        }
        return v.stringValue();
    }

    private static void writeLine(OutputStream out, String line) throws IOException {
        out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /** 数据帧入队；高水位时等待（向上游传导背压），超时或满则以 QUEUE_LIMIT 失败。 */
    public Mono<Void> send(Envelope envelope) {
        return Mono.defer(() -> {
            if (!ready || closed.get()) {
                return Mono.error(new ProtocolException(ErrorCode.ADAPTER_UNAVAILABLE, "adapter not ready"));
            }
            long bytes = estimate(envelope);
            return outQueue.whenWritable()
                    .timeout(Duration.ofSeconds(10))
                    .onErrorMap(TimeoutException.class,
                            e -> new ProtocolException(ErrorCode.QUEUE_LIMIT, "adapter queue stalled"))
                    .then(Mono.defer(() -> {
                        Offer r = outQueue.offer(envelope, bytes, Lane.DATA, null);
                        if (r.accepted()) {
                            return Mono.<Void>empty();
                        }
                        return Mono.<Void>error(new ProtocolException(
                                r == Offer.CLOSED ? ErrorCode.ADAPTER_UNAVAILABLE : ErrorCode.QUEUE_LIMIT, r.name()));
                    }));
        });
    }

    /** 控制帧（fence 更新等）走保留通道并优先发送。 */
    public boolean sendControl(Envelope envelope) {
        return ready && outQueue.offer(envelope, estimate(envelope), Lane.CONTROL, null).accepted();
    }

    private static long estimate(Envelope e) {
        return e.payload().getBytes(StandardCharsets.UTF_8).length + 1024L;
    }

    private void writeLoop(OutputStream out) {
        try {
            while (!closed.get()) {
                Envelope e = outQueue.take(Duration.ofSeconds(1));
                if (e == null) {
                    continue;
                }
                String line = WireAuth.encodeFrame(sessionKey, WireAuth.PLATFORM_TO_ADAPTER, ++outCounter, e);
                out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (IOException | RuntimeException e) {
            shutdown("write failed");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            shutdown("writer interrupted");
        }
    }

    private void readLoop(BoundedLineReader in) {
        String reason = "adapter closed connection";
        try {
            String line;
            while ((line = in.readLine(AdapterProtocol.MAX_WIRE_LINE_BYTES)) != null) {
                Envelope e = WireAuth.decodeFrame(sessionKey, WireAuth.ADAPTER_TO_PLATFORM, ++inCounter, line);
                if (!instanceId.equals(e.instanceId())) {
                    throw new ProtocolException(ErrorCode.STALE_GENERATION, "frame for another instance");
                }
                listener.onEnvelope(this, e);
            }
        } catch (ProtocolException e) {
            reason = "protocol violation: " + e.code();
        } catch (IOException | RuntimeException e) {
            reason = closed.get() ? "closed" : "read failed";
        }
        shutdown(reason);
    }

    private void pump(InputStream stream, String name) {
        BoundedLineReader r = new BoundedLineReader(stream);
        pumpReader(r, name);
    }

    private void pumpReader(BoundedLineReader r) {
        pumpReader(r, "stdout");
    }

    private void pumpReader(BoundedLineReader r, String name) {
        try {
            String line;
            while ((line = r.readLine(16 * 1024)) != null) {
                log.debug("[adapter {} {}] {}", instanceId, name, line.length() > 300 ? line.substring(0, 300) : line);
            }
        } catch (IOException | RuntimeException ignored) {
            // 进程结束
        }
    }

    public boolean isReady() {
        return ready && !closed.get();
    }

    public long generation() {
        return generation;
    }

    public String profileId() {
        return profileId;
    }

    public String adapterVersion() {
        return adapterVersion;
    }

    /** 适配器回环端口（仅供内部测试 / 诊断，不对外暴露）。 */
    public int adapterPort() {
        return adapterPort;
    }

    public long queuedBytes() {
        return outQueue.queuedBytes();
    }

    @Override
    public void close() {
        shutdown("platform closed adapter");
    }

    private void shutdown(String reason) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        ready = false;
        outQueue.close();
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
            // best effort
        }
        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
        log.info("adapter closed instance={} generation={} reason={}", instanceId, generation, reason);
        listener.onClosed(this, reason);
    }
}
