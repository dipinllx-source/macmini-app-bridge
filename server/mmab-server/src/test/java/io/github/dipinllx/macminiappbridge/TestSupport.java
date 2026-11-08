package io.github.dipinllx.macminiappbridge;

import io.github.dipinllx.macminiappbridge.config.MmabClock;
import io.github.dipinllx.macminiappbridge.config.MmabProperties;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;

/** 单元测试公共件。 */
public final class TestSupport {

    private TestSupport() {
    }

    public static Path repoRoot() {
        return Path.of(System.getProperty("mmab.repo.root", "../..")).toAbsolutePath().normalize();
    }

    public static String node() {
        return System.getProperty("mmab.test.node", "node");
    }

    /** 默认时限 + 可推进时钟。 */
    public static MmabProperties properties() {
        return new MmabProperties(null, null, List.of(), null, null, null, null,
                new MmabProperties.Testing(false, true), null);
    }

    public static MmabClock clock() {
        return new MmabClock(properties());
    }

    /** 记录所有发布的事件。 */
    public static final class RecordingPublisher implements ApplicationEventPublisher {
        public final List<Object> events = new ArrayList<>();

        @Override
        public synchronized void publishEvent(Object event) {
            events.add(event);
        }

        public synchronized <T> List<T> of(Class<T> type) {
            return events.stream().filter(type::isInstance).map(type::cast).toList();
        }
    }
}
