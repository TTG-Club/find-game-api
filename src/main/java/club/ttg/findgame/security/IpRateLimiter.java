package club.ttg.findgame.security;

import club.ttg.findgame.config.RateLimitProperties;

import java.util.LinkedHashMap;
import java.util.function.LongSupplier;

/** Fixed windows shared by all API routes, with bounded per-process storage. */
public class IpRateLimiter {

    private final LinkedHashMap<String, Window> windows = new LinkedHashMap<>();
    private final int requests;
    private final int maxClients;
    private final long intervalMillis;
    private final LongSupplier ticker;

    public IpRateLimiter(RateLimitProperties properties) {
        this(properties, () -> System.nanoTime() / 1_000_000);
    }

    IpRateLimiter(RateLimitProperties properties, LongSupplier ticker) {
        this.requests = properties.getRequests();
        this.maxClients = properties.getMaxClients();
        this.intervalMillis = properties.getInterval().toMillis();
        this.ticker = ticker;
    }

    public synchronized Decision consume(String clientAddress) {
        long now = ticker.getAsLong();
        // Every window has the same duration, so insertion order is expiry order.
        // Do not evict live windows: rotating IPs must not reset existing limits.
        while (!windows.isEmpty() && windows.firstEntry().getValue().expiresAt <= now) {
            windows.pollFirstEntry();
        }

        Window window = windows.get(clientAddress);
        if (window == null) {
            if (windows.size() >= maxClients) {
                return new Decision(false, 0, windows.firstEntry().getValue().expiresAt - now);
            }
            window = new Window(now + intervalMillis);
            windows.put(clientAddress, window);
        }

        long resetAfterMillis = window.expiresAt - now;
        if (window.used >= requests) {
            return new Decision(false, 0, resetAfterMillis);
        }
        window.used++;
        return new Decision(true, requests - window.used, resetAfterMillis);
    }

    public record Decision(boolean allowed, int remaining, long resetAfterMillis) {
    }

    private static final class Window {
        private final long expiresAt;
        private int used;

        private Window(long expiresAt) {
            this.expiresAt = expiresAt;
        }
    }
}
