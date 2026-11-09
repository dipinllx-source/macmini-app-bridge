package io.github.dipinllx.macminiappbridge.web;

import io.github.dipinllx.macminiappbridge.config.MmabProperties;
import io.github.dipinllx.macminiappbridge.identity.LaunchTicketService;
import io.github.dipinllx.macminiappbridge.identity.SessionService;
import io.github.dipinllx.macminiappbridge.leases.LeaseManager;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/** 定期清扫：过期租约撤销（并级联关闭连接）、过期会话与票据回收。 */
@Component
public class MaintenanceSweeper implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceSweeper.class);

    private final LeaseManager leases;
    private final SessionService sessions;
    private final LaunchTicketService tickets;
    private final io.github.dipinllx.macminiappbridge.proxy.TransportRegistry transports;
    private final long intervalMillis;
    private ScheduledExecutorService executor;

    public MaintenanceSweeper(LeaseManager leases, SessionService sessions, LaunchTicketService tickets,
            io.github.dipinllx.macminiappbridge.proxy.TransportRegistry transports, MmabProperties properties) {
        this.transports = transports;
        this.leases = leases;
        this.sessions = sessions;
        this.tickets = tickets;
        this.intervalMillis = properties.timeouts().sweepInterval().toMillis();
    }

    public void sweepNow() {
        try {
            transports.keepAlive(); // 先续期活动连接的租约，再清扫过期租约
            leases.sweepExpired();
            sessions.sweep();
            tickets.sweep();
            transports.reapIdlePorts();
            transports.sweepSuspended();
        } catch (RuntimeException e) {
            log.warn("sweep failed: {}", e.getClass().getSimpleName());
        }
    }

    @Override
    public synchronized void start() {
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mmab-sweeper");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(this::sweepNow, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    @Override
    public synchronized boolean isRunning() {
        return executor != null;
    }
}
