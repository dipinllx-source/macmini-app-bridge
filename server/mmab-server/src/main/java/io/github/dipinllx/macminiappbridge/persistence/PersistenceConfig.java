package io.github.dipinllx.macminiappbridge.persistence;

import io.github.dipinllx.macminiappbridge.identity.SessionStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 持久化组件装配（reliable-personal-entry D4）。 */
@Configuration(proxyBeanMethods = false)
public class PersistenceConfig {

    @Bean
    SessionStore sessionStore(Database db) {
        return new SessionStore.Jdbc(db);
    }
}
