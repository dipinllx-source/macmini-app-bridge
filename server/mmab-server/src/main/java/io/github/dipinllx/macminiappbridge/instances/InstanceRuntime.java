package io.github.dipinllx.macminiappbridge.instances;

import io.github.dipinllx.macminiappbridge.config.MmabProperties;
import io.github.dipinllx.macminiappbridge.protocol.ErrorCode;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 一个登记实例的运行态（M1 简化版 actual-state，设计 §7.1）：descriptor、generation、适配器客户端。
 */
public final class InstanceRuntime {

    /** M1 只区分这几种实态；完整状态机（OBSERVING / WAIT_CONFIRM / BACKOFF…）在 M2 起实现。 */
    public enum State { DISABLED, STARTING, READY, BLOCKED, ADAPTER_DOWN }

    private final MmabProperties.Instance config;
    private final MmabProperties.AdapterPackage adapterPackage;
    private final AdapterDescriptor descriptor;
    private final Path resourceRoot;
    private final Path adapterRoot;
    private final AtomicLong generation = new AtomicLong(1);
    private volatile ProcessAdapterClient client;
    private volatile State state;
    private volatile ErrorCode blockedReason;

    InstanceRuntime(MmabProperties.Instance config, MmabProperties.AdapterPackage adapterPackage,
            AdapterDescriptor descriptor, Path resourceRoot, Path adapterRoot, State state, ErrorCode blockedReason) {
        this.config = config;
        this.adapterPackage = adapterPackage;
        this.descriptor = descriptor;
        this.resourceRoot = resourceRoot;
        this.adapterRoot = adapterRoot;
        this.state = state;
        this.blockedReason = blockedReason;
    }

    public String id() {
        return config.id();
    }

    public MmabProperties.Instance config() {
        return config;
    }

    MmabProperties.AdapterPackage adapterPackage() {
        return adapterPackage;
    }

    /** 可能为 null（descriptor 加载失败时实例为 BLOCKED）。 */
    public AdapterDescriptor descriptor() {
        return descriptor;
    }

    /** 原版资源 / fixture 根目录；可能为 null。 */
    public Path resourceRoot() {
        return resourceRoot;
    }

    /** 适配器包目录（adapter.yaml 所在目录）；其 shim/ 子目录提供 {@code adapter:} 注入脚本。可能为 null。 */
    public Path adapterRoot() {
        return adapterRoot;
    }

    public long generation() {
        return generation.get();
    }

    long bumpGeneration() {
        return generation.incrementAndGet();
    }

    public ProcessAdapterClient client() {
        return client;
    }

    void client(ProcessAdapterClient c) {
        this.client = c;
    }

    public State state() {
        return state;
    }

    public ErrorCode blockedReason() {
        return blockedReason;
    }

    void state(State s, ErrorCode reason) {
        this.state = s;
        this.blockedReason = reason;
    }

    public boolean ready() {
        ProcessAdapterClient c = client;
        return state == State.READY && c != null && c.isReady() && c.generation() == generation.get();
    }
}
