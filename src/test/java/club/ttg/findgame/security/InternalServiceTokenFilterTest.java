package club.ttg.findgame.security;

import club.ttg.findgame.config.InternalServiceProperties;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class InternalServiceTokenFilterTest {

    private static final String SECRET = "internal-service-secret";

    @Test
    void acceptsInternalRequestWithValidServiceToken() throws Exception {
        InternalServiceTokenFilter filter = filter(SECRET);
        MockHttpServletRequest request = internalRequest();
        request.addHeader(InternalServiceTokenFilter.SERVICE_TOKEN_HEADER, SECRET);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void rejectsInternalRequestWithoutServiceToken() throws Exception {
        InternalServiceTokenFilter filter = filter(SECRET);
        MockHttpServletRequest request = internalRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void protectsInternalRootPathWithoutTrailingSlash() throws Exception {
        InternalServiceTokenFilter filter = filter(SECRET);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/internal");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void rejectsInternalRequestWithWrongServiceToken() throws Exception {
        InternalServiceTokenFilter filter = filter(SECRET);
        MockHttpServletRequest request = internalRequest();
        request.addHeader(InternalServiceTokenFilter.SERVICE_TOKEN_HEADER, "wrong-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void failsClosedWhenServiceSecretIsNotConfigured() throws Exception {
        InternalServiceTokenFilter filter = filter("");
        MockHttpServletRequest request = internalRequest();
        request.addHeader(InternalServiceTokenFilter.SERVICE_TOKEN_HEADER, SECRET);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void doesNotFilterPublicOrUserRoutes() throws Exception {
        InternalServiceTokenFilter filter = filter("");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/games");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    private InternalServiceTokenFilter filter(String secret) {
        InternalServiceProperties properties = new InternalServiceProperties();
        properties.setServiceSecret(secret);
        return new InternalServiceTokenFilter(properties);
    }

    private MockHttpServletRequest internalRequest() {
        return new MockHttpServletRequest("POST", "/api/v1/internal/games/test");
    }
}
