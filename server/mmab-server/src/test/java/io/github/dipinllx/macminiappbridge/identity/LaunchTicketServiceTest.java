package io.github.dipinllx.macminiappbridge.identity;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dipinllx.macminiappbridge.TestSupport;
import io.github.dipinllx.macminiappbridge.config.MmabClock;
import io.github.dipinllx.macminiappbridge.identity.LaunchTicketService.Outcome;
import io.github.dipinllx.macminiappbridge.identity.LaunchTicketService.Rejection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/** 开窗票据：一次性、短时过期、绑定目标端口 / Origin（设计 §5.3）。 */
class LaunchTicketServiceTest {

    static final String CONSOLE = "https://192.0.2.10:8443";
    static final String TARGET = "https://192.0.2.10:8444";

    private final MmabClock clock = TestSupport.clock();
    private final LaunchTicketService tickets = new LaunchTicketService(clock, TestSupport.properties());

    private String issue() {
        return tickets.issue("qwen-01", 8444, TARGET, CONSOLE, "cs-1", "l-1", 1, 1).token();
    }

    @Test
    void validTicketExchangesExactlyOnce() {
        String t = issue();
        Outcome ok = tickets.exchange(t, CONSOLE, 8444, TARGET);
        assertThat(ok.ok()).isTrue();
        assertThat(ok.ticket().leaseId()).isEqualTo("l-1");
        assertThat(tickets.exchange(t, CONSOLE, 8444, TARGET).rejection()).isEqualTo(Rejection.UNKNOWN_OR_REPLAYED);
    }

    @Test
    void expiresAfterThirtySeconds() {
        String fresh = issue();
        String stale = issue();
        clock.advance(Duration.ofSeconds(29));
        assertThat(tickets.exchange(fresh, CONSOLE, 8444, TARGET).ok()).isTrue();
        clock.advance(Duration.ofSeconds(1));
        assertThat(tickets.exchange(stale, CONSOLE, 8444, TARGET).rejection()).isEqualTo(Rejection.EXPIRED);
        assertThat(tickets.exchange(stale, CONSOLE, 8444, TARGET).rejection())
                .as("an expired ticket is also consumed").isEqualTo(Rejection.UNKNOWN_OR_REPLAYED);
    }

    @Test
    void boundToTargetPortConsoleOriginAndTargetOrigin() {
        String a = issue();
        assertThat(tickets.exchange(a, CONSOLE, 8445, "https://192.0.2.10:8445").rejection())
                .isEqualTo(Rejection.WRONG_PORT);
        assertThat(tickets.exchange(a, CONSOLE, 8444, TARGET).rejection())
                .as("a failed attempt burns the ticket").isEqualTo(Rejection.UNKNOWN_OR_REPLAYED);

        assertThat(tickets.exchange(issue(), TARGET, 8444, TARGET).rejection()).isEqualTo(Rejection.WRONG_ORIGIN);
        assertThat(tickets.exchange(issue(), "null", 8444, TARGET).rejection()).isEqualTo(Rejection.WRONG_ORIGIN);
        assertThat(tickets.exchange(issue(), null, 8444, TARGET).rejection()).isEqualTo(Rejection.WRONG_ORIGIN);
        assertThat(tickets.exchange(issue(), "https://127.0.0.1:8443", 8444, TARGET).rejection())
                .isEqualTo(Rejection.WRONG_ORIGIN);
        assertThat(tickets.exchange(issue(), CONSOLE, 8444, "https://127.0.0.1:8444").rejection())
                .as("exchange must happen on exactly the bound target origin").isEqualTo(Rejection.WRONG_HOST);
    }

    @Test
    void garbageAndUnknownTokensAreRejected() {
        assertThat(tickets.exchange(null, CONSOLE, 8444, TARGET).ok()).isFalse();
        assertThat(tickets.exchange("x", CONSOLE, 8444, TARGET).ok()).isFalse();
        assertThat(tickets.exchange(Tokens.random(), CONSOLE, 8444, TARGET).rejection())
                .isEqualTo(Rejection.UNKNOWN_OR_REPLAYED);
    }

    @Test
    void concurrentDoubleSpendHasExactlyOneWinner() throws Exception {
        String t = issue();
        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return tickets.exchange(t, CONSOLE, 8444, TARGET).ok();
            }));
        }
        start.countDown();
        int winners = 0;
        for (Future<Boolean> f : results) {
            winners += f.get() ? 1 : 0;
        }
        pool.shutdown();
        assertThat(winners).isEqualTo(1);
    }

    @Test
    void sweepDropsExpiredTickets() {
        issue();
        issue();
        assertThat(tickets.outstanding()).isEqualTo(2);
        clock.advance(Duration.ofSeconds(31));
        tickets.sweep();
        assertThat(tickets.outstanding()).isZero();
    }
}
