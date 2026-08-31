package club.ttg.findgame.security;

import club.ttg.findgame.config.SecurityConfiguration;
import club.ttg.findgame.game.GameController;
import club.ttg.findgame.game.GameService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(GameController.class)
@Import(SecurityConfiguration.class)
@TestPropertySource(properties = {
        "auth-service.jwt-secret=0123456789abcdef0123456789abcdef",
        "rate-limit.requests=2"
})
class RateLimitSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GameService service;

    @Test
    void rateLimitsBeforeJwtValidationWithoutReachingTheController() throws Exception {
        for (int index = 0; index < 2; index++) {
            mockMvc.perform(get("/api/v1/games").header("Authorization", "Bearer invalid-" + index))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string("X-RateLimit-Limit", "2"));
        }
        mockMvc.perform(get("/api/v1/games").header("Authorization", "Bearer another-invalid-token"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
        mockMvc.perform(get("/api/v1/games").with(request -> {
                    request.setRemoteAddr("192.0.2.2");
                    return request;
                }).header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
}
