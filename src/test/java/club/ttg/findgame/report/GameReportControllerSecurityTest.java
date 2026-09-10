package club.ttg.findgame.report;

import club.ttg.findgame.common.ApiExceptionHandler;
import club.ttg.findgame.config.SecurityConfiguration;
import club.ttg.findgame.game.GameModerationController;
import club.ttg.findgame.game.GameService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({GameReportController.class, GameModerationController.class})
@Import({SecurityConfiguration.class, ApiExceptionHandler.class})
@TestPropertySource(properties = "auth-service.jwt-secret=" + GameReportControllerSecurityTest.SECRET)
class GameReportControllerSecurityTest {

    static final String SECRET = "0123456789abcdef0123456789abcdef";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GameReportService service;

    @MockitoBean
    private GameService gameService;

    @Test
    void guestCannotReportGame() throws Exception {
        mockMvc.perform(post("/api/v1/games/{gameId}/reports", UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"reason\":\"SPAM\"}"))
                .andExpect(status().isUnauthorized());

        verify(service, never()).create(any(), any(), any());
    }

    @Test
    void authenticatedUserReportsOwnIdentity() throws Exception {
        UUID reporterId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/games/{gameId}/reports", gameId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + issueToken(reporterId, "USER"))
                        .contentType("application/json")
                        .content("{\"reason\":\"SPAM\",\"details\":\"Повторяющееся объявление\"}"))
                .andExpect(status().isCreated());

        verify(service).create(eq(reporterId), eq(gameId), any());
    }

    @Test
    void regularUserCannotReadReportQueue() throws Exception {
        mockMvc.perform(get("/api/v1/moderation/game-reports")
                        .header(HttpHeaders.AUTHORIZATION,
                                "Bearer " + issueToken(UUID.randomUUID(), "USER")))
                .andExpect(status().isForbidden());

        verify(service, never()).findAll(anyInt(), anyInt());
    }

    @Test
    void moderatorReadsReportQueue() throws Exception {
        mockMvc.perform(get("/api/v1/moderation/game-reports")
                        .header(HttpHeaders.AUTHORIZATION,
                                "Bearer " + issueToken(UUID.randomUUID(), "MODERATOR")))
                .andExpect(status().isOk());

        verify(service).findAll(0, 20);
    }

    @Test
    void moderatorCanHideAllGamesOfReportedMaster() throws Exception {
        UUID gameId = UUID.randomUUID();

        mockMvc.perform(delete("/api/v1/moderation/games/{gameId}/master-games", gameId)
                        .header(HttpHeaders.AUTHORIZATION,
                                "Bearer " + issueToken(UUID.randomUUID(), "MODERATOR")))
                .andExpect(status().isNoContent());

        verify(gameService).deleteAllByReportedGame(eq(gameId), any());
    }

    private String issueToken(UUID userId, String role) {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .subject(userId.toString())
                .claim("roles", List.of(role))
                .issuedAt(Date.from(Instant.now().minus(1, ChronoUnit.MINUTES)))
                .expiration(Date.from(Instant.now().plus(1, ChronoUnit.HOURS)))
                .signWith(key)
                .compact();
    }
}
