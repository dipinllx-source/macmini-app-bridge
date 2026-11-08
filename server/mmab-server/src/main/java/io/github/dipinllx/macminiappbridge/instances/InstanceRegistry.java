package io.github.dipinllx.macminiappbridge.instances;

import io.github.dipinllx.macminiappbridge.audit.AuditLog;
import io.github.dipinllx.macminiappbridge.config.MmabClock;
import io.github.dipinllx.macminiappbridge.config.MmabProperties;
import io.github.dipinllx.macminiappbridge.leases.Lease;
import io.github.dipinllx.macminiappbridge.leases.LeaseEvents;
import io.github.dipinllx.macminiappbridge.leases.LeaseManager;
import io.github.dipinllx.macminiappbridge.protocol.Envelope;
import io.github.dipinllx.macminiappbridge.protocol.ErrorCode;
import io.github.dipinllx.macminiappbridge.protocol.MessageKind;
import io.github.dipinllx.macminiappbridge.protocol.StrategyFamily;
import io.github.dipinllx.macminiappbridge.proxy.TransportRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 实例登记与适配器生命周期（M1：启动时拉起已启用实例的适配器；generation 在适配器重建时递增）。
 * 完整的 desired/actual-state 对账（设计 §7）不在 M1 范围。
 */
@Component
public class InstanceRegistry implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(InstanceRegistry.class);

    private final MmabProperties properties;
    private final LeaseManager leases;
    private final TransportRegistry transports;
    private final ApplicationEventPublisher events;
    private final AuditLog audit;
    private final MmabClock clock;
    private final Path home;
    private final Map<String, InstanceRuntime> runtimes = new LinkedHashMap<>();
    private final AtomicLong controlSequence = new AtomicLong();
    private volatile boolean running;

    public InstanceRegistry(MmabProperties properties, LeaseManager leases, TransportRegistry transports,
            ApplicationEventPublisher events, AuditLog audit, MmabClock clock) {
        this.properties = properties;
        this.leases = leases;
        this.transports = transports;
        this.events = events;
        this.audit = audit;
        this.clock = clock;
        this.home = Path.of(properties.home()).toAbsolutePath().normalize();
        for (MmabProperties.Instance i : properties.instances()) {
            runtimes.put(i.id(), prepare(i));
        }
    }

    private InstanceRuntime prepare(MmabProperties.Instance i) {
        if (!i.enabled()) {
            return new InstanceRuntime(i, null, null, null, InstanceRuntime.State.DISABLED, null);
        }
        MmabProperties.AdapterPackage pkg = properties.adapters().stream()
                .filter(a -> a.appId().equals(i.appId())).findFirst().orElse(null);
        if (pkg == null || pkg.descriptor() == null) {
            return blocked(i, pkg, null, ErrorCode.COMPATIBILITY_BLOCKED, "no adapter package");
        }
        AdapterDescriptor d;
        try {
            d = AdapterDescriptorLoader.load(home.resolve(pkg.descriptor()));
        } catch (IOException | RuntimeException e) {
            return blocked(i, pkg, null, ErrorCode.COMPATIBILITY_BLOCKED, "descriptor invalid");
        }
        if (!d.appId().equals(i.appId()) || !d.strategy().id().equals(i.strategy())) {
            return blocked(i, pkg, d, ErrorCode.COMPATIBILITY_BLOCKED, "strategy/appId mismatch");
        }
        if (d.strategy() == StrategyFamily.TEST_MOCK && !properties.testing().allowTestAdapters()) {
            return blocked(i, pkg, d, ErrorCode.COMPATIBILITY_BLOCKED, "test adapter not allowed");
        }
        if (!d.supportsCurrentProtocol()) {
            return blocked(i, pkg, d, ErrorCode.PROTOCOL_VERSION_UNSUPPORTED, "protocol range");
        }
        Path resources = null;
        if (pkg.resources() != null) {
            resources = home.resolve(pkg.resources()).normalize();
            if (!resources.startsWith(home) || !Files.isDirectory(resources)) {
                return blocked(i, pkg, d, ErrorCode.COMPATIBILITY_BLOCKED, "resources missing");
            }
        }
        return new InstanceRuntime(i, pkg, d, resources, InstanceRuntime.State.STARTING, null);
    }

    private InstanceRuntime blocked(MmabProperties.Instance i, MmabProperties.AdapterPackage pkg,
            AdapterDescriptor d, ErrorCode code, String detail) {
        log.warn("instance {} blocked: {} ({})", i.id(), code, detail);
        return new InstanceRuntime(i, pkg, d, null, InstanceRuntime.State.BLOCKED, code);
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void start() {
        for (InstanceRuntime rt : runtimes.values()) {
            if (rt.state() == InstanceRuntime.State.STARTING) {
                startAdapter(rt);
            }
        }
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        for (InstanceRuntime rt : runtimes.values()) {
            ProcessAdapterClient c = rt.client();
            if (c != null) {
                rt.client(null);
                c.close();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return SmartLifecycle.DEFAULT_PHASE - 2048;
    }

    private void startAdapter(InstanceRuntime rt) {
        long gen = rt.generation();
        try {
            ProcessAdapterClient c = ProcessAdapterClient.start(rt.adapterPackage().command(), home, rt.id(), gen,
                    rt.descriptor().profileId(), properties.timeouts().adapterStartup(), new AdapterListener(rt));
            rt.client(c);
            rt.state(InstanceRuntime.State.READY, null);
            pushFence(rt);
            audit.record(null, "adapter.start", rt.id(), "OK", "generation=" + gen);
        } catch (IOException | RuntimeException e) {
            rt.state(InstanceRuntime.State.BLOCKED, ErrorCode.ADAPTER_UNAVAILABLE);
            audit.record(null, "adapter.start", rt.id(), ErrorCode.ADAPTER_UNAVAILABLE.name(), "generation=" + gen);
        }
    }

    /**
     * 重建适配器（模拟 App / renderer context 重建）：generation 递增，旧租约 fence 失效，
     * 旧连接全部关闭，旧 generation 的任何帧此后都被拒（设计 §9.2）。
     */
    public synchronized long restartAdapter(String instanceId) {
        InstanceRuntime rt = require(instanceId);
        if (rt.descriptor() == null || rt.adapterPackage() == null) {
            throw new IllegalStateException("instance has no adapter");
        }
        ProcessAdapterClient old = rt.client();
        rt.client(null);
        rt.state(InstanceRuntime.State.STARTING, null);
        long gen = rt.bumpGeneration();
        leases.revokeInstance(instanceId, ErrorCode.STALE_GENERATION);
        events.publishEvent(new LeaseEvents.Revocation(instanceId, null, null, null, ErrorCode.STALE_GENERATION));
        if (old != null) {
            old.close();
        }
        audit.record(null, "instance.generation", instanceId, "OK", "generation=" + gen);
        startAdapter(rt);
        return gen;
    }

    /** 实例端口无法绑定：不抢占、不换端口，实例阻塞并停掉其适配器（设计 §5.1、§7.1）。 */
    public void markPortBlocked(String instanceId) {
        InstanceRuntime rt = require(instanceId);
        ProcessAdapterClient c = rt.client();
        rt.client(null);
        rt.state(InstanceRuntime.State.BLOCKED, ErrorCode.PORT_OWNER_MISMATCH);
        if (c != null) {
            c.close();
        }
    }

    public Optional<InstanceRuntime> get(String instanceId) {
        return Optional.ofNullable(runtimes.get(instanceId));
    }

    public InstanceRuntime require(String instanceId) {
        InstanceRuntime rt = runtimes.get(instanceId);
        if (rt == null) {
            throw new IllegalArgumentException("unknown instance");
        }
        return rt;
    }

    public Collection<InstanceRuntime> all() {
        return runtimes.values();
    }

    public long generation(String instanceId) {
        InstanceRuntime rt = runtimes.get(instanceId);
        return rt == null ? -1 : rt.generation();
    }

    // ------------------------------------------------------------------ fence

    @EventListener
    public void onFenceChanged(LeaseEvents.FenceChanged e) {
        get(e.instanceId()).ifPresent(this::pushFence);
    }

    /** 把当前 (generation, lease, fence) 推给适配器：适配器据此拒绝旧租约 / 旧 generation 的帧。 */
    void pushFence(InstanceRuntime rt) {
        ProcessAdapterClient c = rt.client();
        if (c == null || rt.descriptor() == null) {
            return;
        }
        Optional<Lease> lease = leases.current(rt.id()).filter(l -> l.generation() == rt.generation());
        Envelope fence = Envelope.builder()
                .profileId(rt.descriptor().profileId())
                .instanceId(rt.id())
                .processGeneration(rt.generation())
                .connectionId(Envelope.PLATFORM_CONNECTION)
                .leaseId(lease.map(Lease::leaseId).orElse(Envelope.NO_LEASE))
                .fencingToken(lease.map(Lease::fencingToken).orElse(0L))
                .sequence(controlSequence.incrementAndGet())
                .messageKind(MessageKind.CONTROL)
                .operation("mmab.fence")
                .deadline(clock.instant().plus(Duration.ofSeconds(30)))
                .build();
        if (!c.sendControl(fence)) {
            log.warn("fence update not queued for {}", rt.id());
        }
    }

    // ------------------------------------------------------------------ adapter callbacks

    private final class AdapterListener implements ProcessAdapterClient.Listener {
        private final InstanceRuntime rt;

        AdapterListener(InstanceRuntime rt) {
            this.rt = rt;
        }

        @Override
        public void onEnvelope(ProcessAdapterClient source, Envelope e) {
            if (source != rt.client() || e.processGeneration() != rt.generation()) {
                return; // 旧 generation 的迟到帧：丢弃（执行状态未知）
            }
            if (Envelope.PLATFORM_CONNECTION.equals(e.connectionId())) {
                if (e.error() != null) {
                    log.warn("adapter {} control error {}", rt.id(), e.error().code());
                }
                return;
            }
            if (!leases.isCurrent(rt.id(), e.leaseId(), e.fencingToken(), e.processGeneration())) {
                return; // 租约已撤销 / 换代后的迟到帧
            }
            transports.route(rt.id(), e);
        }

        @Override
        public void onClosed(ProcessAdapterClient source, String reason) {
            if (source != rt.client()) {
                return;
            }
            rt.state(InstanceRuntime.State.ADAPTER_DOWN, ErrorCode.ADAPTER_UNAVAILABLE);
            if (running) {
                leases.revokeInstance(rt.id(), ErrorCode.ADAPTER_UNAVAILABLE);
                events.publishEvent(new LeaseEvents.Revocation(rt.id(), null, null, null,
                        ErrorCode.ADAPTER_UNAVAILABLE));
                audit.record(null, "adapter.down", rt.id(), ErrorCode.ADAPTER_UNAVAILABLE.name(), null);
            }
        }
    }
}
