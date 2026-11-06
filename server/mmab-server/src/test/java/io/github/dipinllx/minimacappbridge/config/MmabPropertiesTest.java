package io.github.dipinllx.minimacappbridge.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class MmabPropertiesTest {

    private static MmabProperties.Instance instance(String id, int port) {
        return new MmabProperties.Instance(id, "app", id, port, "electron-main-inject", false);
    }

    @Test
    void rejectsPortCollisionWithConsole() {
        assertThatThrownBy(() -> new MmabProperties(null, new MmabProperties.Console(8443, "127.0.0.1"),
                List.of(instance("qwen-01", 8443))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsDuplicateInstanceId() {
        assertThatThrownBy(() -> new MmabProperties(null, null,
                List.of(instance("qwen-01", 8444), instance("qwen-01", 8446))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsWildcardBind() {
        assertThatThrownBy(() -> new MmabProperties.Console(8443, "0.0.0.0"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
