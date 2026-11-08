package io.github.dipinllx.macminiappbridge.api;

import io.github.dipinllx.macminiappbridge.config.MmabProperties;
import io.github.dipinllx.macminiappbridge.protocol.AdapterProtocol;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 只读健康接口（控制台端口）。平台保留路径前缀 {@code /__mmab/}（设计文档 §15）。
 *
 * <p>注意：这是"平台进程在跑"，不是设计 §8 的 L0-L4 应用健康。
 */
@RestController
@RequestMapping("/__mmab/api/v1")
public class HealthController {

    static final String STATUS = "m1-offline";

    private final MmabProperties properties;

    public HealthController(MmabProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/health")
    public Mono<HealthResponse> health() {
        return Mono.just(new HealthResponse(
                "MacminiAppBridge",
                "mmab",
                properties.platform().version(),
                STATUS,
                AdapterProtocol.CURRENT + "-" + AdapterProtocol.STATUS,
                properties.instances().size(),
                "M1 offline protocol & security skeleton: mock adapters only; no real app is bridged."));
    }
}
