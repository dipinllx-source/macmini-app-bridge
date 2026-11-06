package io.github.dipinllx.minimacappbridge;

import io.github.dipinllx.minimacappbridge.config.MmabProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * MiniMacAppBridge（miniMac App 引桥）Java 核心入口。
 *
 * <p>设计骨架：只提供只读健康接口，不含认证、TLS、实例监听、租约、代理或任何桥接实现，
 * 不可用于远程访问。
 */
@SpringBootApplication
@EnableConfigurationProperties(MmabProperties.class)
public class MiniMacAppBridgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(MiniMacAppBridgeApplication.class, args);
    }
}
