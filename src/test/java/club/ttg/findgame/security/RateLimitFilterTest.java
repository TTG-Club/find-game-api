package club.ttg.findgame.security;

import club.ttg.findgame.config.RateLimitProperties;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class RateLimitFilterTest {

    @Test
    void sharesQuotaAcrossPathsMethodsAndForgedIdentityHeaders() throws Exception {
        RateLimitFilter filter = filter(true);
        MockHttpServletResponse allowed = perform(filter, request("GET", "/api/v1/games"));
        assertThat(allowed.getHeader("X-RateLimit-Limit")).isEqualTo("1");
        assertThat(allowed.getHeader("X-RateLimit-Remaining")).isEqualTo("0");

        MockHttpServletRequest forged = request("POST", "/api/v1/internal/users");
        forged.addHeader("X-Forwarded-For", "198.51.100.1");
        forged.addHeader("X-Real-IP", "198.51.100.2");
        forged.addHeader("Forwarded", "for=198.51.100.3");
        forged.addHeader("Authorization", "Bearer rotated-token");
        forged.addHeader("X-Service-Token", "rotated-token");
        MockHttpServletResponse rejected = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(forged, rejected, chain);

        verifyNoInteractions(chain);
        assertThat(rejected.getStatus()).isEqualTo(429);
        assertThat(rejected.getContentType()).startsWith("application/problem+json");
        assertThat(rejected.getContentAsString()).contains("\"status\":429");
        assertThat(Long.parseLong(rejected.getHeader("Retry-After"))).isBetween(1L, 60L);
        assertThat(Long.parseLong(rejected.getHeader("X-RateLimit-Reset")))
                .isGreaterThan(System.currentTimeMillis());
        assertThat(rejected.getHeader("Cache-Control")).isEqualTo("no-store");
    }

    @Test
    void healthDocumentationAndAsyncDispatchDoNotConsumeApiQuota() throws Exception {
        RateLimitFilter filter = filter(true);
        for (String path : new String[]{"/actuator/health", "/swagger-ui/index.html", "/v3/api-docs", "/apix"}) {
            assertThat(perform(filter, request("GET", path)).getHeader("X-RateLimit-Limit")).isNull();
        }
        MockHttpServletRequest async = request("GET", "/api/v1/games/test/chat/stream");
        async.setDispatcherType(DispatcherType.ASYNC);
        assertThat(perform(filter, async).getHeader("X-RateLimit-Limit")).isNull();
        assertThat(perform(filter, request("GET", "/api/v1/games/test/chat/stream")).getStatus()).isEqualTo(200);
        assertThat(perform(filter, request("GET", "/api/v1/games/test/chat/stream")).getStatus()).isEqualTo(429);
    }

    @Test
    void protectsApiRootUnderAContextPathAndCountsOptions() throws Exception {
        RateLimitFilter filter = filter(true);
        MockHttpServletRequest request = request("OPTIONS", "/service/api");
        request.setContextPath("/service");
        assertThat(perform(filter, request).getHeader("X-RateLimit-Limit")).isEqualTo("1");
        assertThat(perform(filter, request("GET", "/api/v1/games")).getStatus()).isEqualTo(429);
    }

    @Test
    void canDisableTheLimiter() throws Exception {
        RateLimitFilter filter = filter(false);
        for (int index = 0; index < 3; index++) {
            MockHttpServletRequest request = request("POST", "/api/v1/games");
            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);
            filter.doFilter(request, response, chain);
            verify(chain).doFilter(request, response);
            assertThat(response.getHeader("X-RateLimit-Limit")).isNull();
        }
    }

    private RateLimitFilter filter(boolean enabled) {
        RateLimitProperties properties = new RateLimitProperties();
        properties.setRequests(1);
        properties.setEnabled(enabled);
        return new RateLimitFilter(properties);
    }

    private MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr("192.0.2.1");
        return request;
    }

    private MockHttpServletResponse perform(RateLimitFilter filter, MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, mock(FilterChain.class));
        return response;
    }
}
