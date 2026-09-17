package club.ttg.findgame.game;

import club.ttg.findgame.config.SecurityConfiguration;
import club.ttg.findgame.game.api.GameStatisticsResponse;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Проверяет реальную цепочку JWT-авторизации endpoint статистики. */
@WebMvcTest(GameStatisticsController.class)
@Import(SecurityConfiguration.class)
@TestPropertySource(properties = "auth-service.jwt-secret=" + GameStatisticsControllerSecurityTest.SECRET)
class GameStatisticsControllerSecurityTest {

    static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final String STATISTICS_PATH = "/api/v1/admin/statistics/games";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GameStatisticsService service;

    /** Гость не получает статистику. */
    @Test
    void guestCannotReadStatistics() throws Exception {
        mockMvc.perform(get(STATISTICS_PATH)).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    /** Обычный пользователь и модератор не имеют прав администратора. */
    @ParameterizedTest
    @ValueSource(strings = {"USER", "MODERATOR"})
    void nonAdminCannotReadStatistics(String role) throws Exception {
        mockMvc.perform(get(STATISTICS_PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + issueToken(role)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    /** Администратор получает оба именованных счётчика. */
    @Test
    void adminCanReadStatistics() throws Exception {
        given(service.getStatistics()).willReturn(new GameStatisticsResponse(12, 4));

        mockMvc.perform(get(STATISTICS_PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + issueToken("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(12))
                .andExpect(jsonPath("$.completed").value(4));
    }

    /** Выпускает тестовый JWT с указанной ролью. */
    private String issueToken(String role) {
        return Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .claim("roles", List.of(role))
                .issuedAt(Date.from(Instant.now().minusSeconds(60)))
                .expiration(Date.from(Instant.now().plusSeconds(3600)))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }
}
