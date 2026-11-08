package io.github.dipinllx.macminiappbridge.config;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * 平台统一时钟（票据、会话、租约过期都以它为准）。生产恒为系统 UTC 时钟；
 * 只有 {@code mmab.testing.clock-control=true} 时允许测试推进时间。
 */
@Component
public class MmabClock extends Clock {

    private final boolean controllable;
    private final AtomicReference<Duration> offset = new AtomicReference<>(Duration.ZERO);

    public MmabClock(MmabProperties properties) {
        this.controllable = properties.testing().clockControl();
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return Instant.now().plus(offset.get());
    }

    /** 仅测试：推进时钟。 */
    public void advance(Duration d) {
        if (!controllable) {
            throw new IllegalStateException("clock control disabled");
        }
        offset.updateAndGet(o -> o.plus(d));
    }
}
