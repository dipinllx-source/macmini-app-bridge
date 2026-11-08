package io.github.dipinllx.macminiappbridge.identity;

import io.github.dipinllx.macminiappbridge.config.MmabClock;
import io.github.dipinllx.macminiappbridge.config.MmabProperties;
import io.github.dipinllx.macminiappbridge.protocol.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 平台操作者口令校验 + 全局失败限流（设计 §6.3：认证限流按全局预算）。
 * 口令与任何应用账号无关；只比对配置中的 PBKDF2 hash，明文不落日志。
 */
@Component
public class OperatorAuthenticator {

    static final int MAX_FAILURES = 10;
    static final Duration WINDOW = Duration.ofMinutes(1);

    private final String passwordHash;
    private final MmabClock clock;
    private final Deque<Instant> failures = new ArrayDeque<>();

    public OperatorAuthenticator(MmabProperties properties, MmabClock clock) {
        String hash = properties.operator().passwordHash();
        if (hash != null && !PasswordHasher.isWellFormed(hash)) {
            throw new IllegalArgumentException("mmab.operator.password-hash is not a valid pbkdf2-sha256 hash");
        }
        this.passwordHash = hash;
        this.clock = clock;
    }

    public boolean configured() {
        return passwordHash != null;
    }

    /** @return 空 = 通过；否则为拒绝原因 */
    public Optional<ErrorCode> verify(char[] password) {
        if (passwordHash == null) {
            return Optional.of(ErrorCode.SETUP_REQUIRED);
        }
        Instant now = clock.instant();
        synchronized (failures) {
            while (!failures.isEmpty() && failures.peekFirst().isBefore(now.minus(WINDOW))) {
                failures.pollFirst();
            }
            if (failures.size() >= MAX_FAILURES) {
                return Optional.of(ErrorCode.RATE_LIMITED);
            }
        }
        if (PasswordHasher.verify(password, passwordHash)) {
            return Optional.empty();
        }
        synchronized (failures) {
            failures.addLast(now);
        }
        return Optional.of(ErrorCode.UNAUTHENTICATED);
    }
}
