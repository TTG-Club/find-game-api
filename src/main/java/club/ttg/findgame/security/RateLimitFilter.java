package club.ttg.findgame.security;

import club.ttg.findgame.config.RateLimitProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.NonNull;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Runs before authentication, request body parsing and application logging. */
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimitProperties properties;
    private final IpRateLimiter limiter;

    public RateLimitFilter(RateLimitProperties properties) {
        this.properties = properties;
        this.limiter = new IpRateLimiter(properties);
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        // The servlet path is decoded by the container; raw URI checks allow /%61pi/... bypasses.
        String path = request.getServletPath();
        if (path.isEmpty()) {
            path = request.getRequestURI().substring(request.getContextPath().length());
        }
        return !properties.isEnabled() || !(path.equals("/api") || path.startsWith("/api/"));
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {
        // Only the servlet container may resolve forwarded IPs, using its trusted proxy rules.
        // Never use raw X-Forwarded-For, X-Real-IP, JWTs or service tokens as rate limit keys.
        IpRateLimiter.Decision decision = limiter.consume(request.getRemoteAddr());
        response.setHeader("X-RateLimit-Limit", Integer.toString(properties.getRequests()));
        response.setHeader("X-RateLimit-Remaining", Integer.toString(decision.remaining()));
        // Epoch milliseconds, matching core-app / nuxt-security.
        response.setHeader("X-RateLimit-Reset",
                Long.toString(System.currentTimeMillis() + decision.resetAfterMillis()));

        if (decision.allowed()) {
            filterChain.doFilter(request, response);
            return;
        }

        response.setStatus(429);
        response.setHeader(HttpHeaders.RETRY_AFTER,
                Long.toString(Math.max(1, (decision.resetAfterMillis() + 999) / 1000)));
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("""
                {"type":"about:blank","title":"Too Many Requests","status":429,
                "detail":"API request rate limit exceeded. Retry after the interval specified in Retry-After."}
                """);
    }
}
