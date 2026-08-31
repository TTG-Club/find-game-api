package club.ttg.findgame.security;

import club.ttg.findgame.config.RateLimitProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class IpRateLimiterTest {

    private final AtomicLong time = new AtomicLong();

    @Test
    void allowsExactly75RequestsAndRestoresQuotaAtTheWindowBoundary() {
        IpRateLimiter limiter = new IpRateLimiter(new RateLimitProperties(), time::get);

        for (int index = 0; index < 75; index++) {
            assertThat(limiter.consume("192.0.2.1").allowed()).isTrue();
        }
        assertThat(limiter.consume("192.0.2.1"))
                .isEqualTo(new IpRateLimiter.Decision(false, 0, 60_000));
        time.set(59_999);
        assertThat(limiter.consume("192.0.2.1"))
                .isEqualTo(new IpRateLimiter.Decision(false, 0, 1));
        time.set(60_000);
        assertThat(limiter.consume("192.0.2.1"))
                .isEqualTo(new IpRateLimiter.Decision(true, 74, 60_000));
    }

    @Test
    void keepsSeparateWindowsForDifferentClients() {
        IpRateLimiter limiter = limiter(1, 10);
        assertThat(limiter.consume("192.0.2.1").allowed()).isTrue();
        time.set(500);
        assertThat(limiter.consume("192.0.2.2").allowed()).isTrue();
        assertThat(limiter.consume("192.0.2.1").allowed()).isFalse();
        time.set(1000);
        assertThat(limiter.consume("192.0.2.1").allowed()).isTrue();
        assertThat(limiter.consume("192.0.2.2").allowed()).isFalse();
    }

    @Test
    void boundsMemoryWithoutEvictingActiveLimitsAndReclaimsExpiredClients() {
        IpRateLimiter limiter = limiter(2, 2);
        limiter.consume("192.0.2.1");
        limiter.consume("192.0.2.1");
        time.set(500);
        limiter.consume("192.0.2.2");
        assertThat(limiter.consume("192.0.2.3"))
                .isEqualTo(new IpRateLimiter.Decision(false, 0, 500));
        assertThat(limiter.consume("192.0.2.1").allowed()).isFalse();
        assertThat(limiter.consume("192.0.2.2").allowed()).isTrue();
        time.set(1000);
        assertThat(limiter.consume("192.0.2.3").allowed()).isTrue();
        assertThat(limiter.consume("192.0.2.2").allowed()).isFalse();
    }

    @Test
    void concurrentRequestsCannotExceedTheQuota() throws Exception {
        IpRateLimiter limiter = limiter(75, 10);
        try (var executor = Executors.newFixedThreadPool(16)) {
            var attempts = new ArrayList<Callable<Boolean>>();
            for (int index = 0; index < 500; index++) {
                attempts.add(() -> limiter.consume("192.0.2.1").allowed());
            }
            int accepted = 0;
            for (Future<Boolean> result : executor.invokeAll(attempts)) {
                if (result.get()) {
                    accepted++;
                }
            }
            assertThat(accepted).isEqualTo(75);
        }
    }

    private IpRateLimiter limiter(int requests, int maxClients) {
        RateLimitProperties properties = new RateLimitProperties();
        properties.setRequests(requests);
        properties.setMaxClients(maxClients);
        properties.setInterval(Duration.ofSeconds(1));
        return new IpRateLimiter(properties, time::get);
    }
}
