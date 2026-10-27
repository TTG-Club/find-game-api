package club.ttg.findgame.review;

import club.ttg.findgame.common.ApiExceptionHandler;
import club.ttg.findgame.config.SecurityConfiguration;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(GameReviewController.class)
@Import({SecurityConfiguration.class, ApiExceptionHandler.class})
@TestPropertySource(
        properties = "auth-service.jwt-secret=" + GameReviewControllerSecurityTest.SECRET)
class GameReviewControllerSecurityTest {

    static final String SECRET = "0123456789abcdef0123456789abcdef";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GameReviewService service;

    @Test
    void guestCannotRate() throws Exception {
        mockMvc.perform(post("/api/v1/games/{gameId}/reviews", UUID.randomUUID())
                        .contentType("application/json")
                        .content(validRequest(UUID.randomUUID())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authorComesFromJwtSubject() throws Exception {
        UUID authorId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();

        // Оценку подписывает токен: чужую от своего имени не поставить.
        mockMvc.perform(post("/api/v1/games/{gameId}/reviews", gameId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + issueToken(authorId))
                        .contentType("application/json")
                        .content(validRequest(targetId)))
                .andExpect(status().isOk());

        verify(service).review(eq(authorId), eq(gameId), any());
    }

    @Test
    void verdictIsRequired() throws Exception {
        UUID authorId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();

        // Отзыв без вердикта не считается: репутацию строят «пальцы», а не текст.
        mockMvc.perform(post("/api/v1/games/{gameId}/reviews", gameId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + issueToken(authorId))
                        .contentType("application/json")
                        .content("""
                                {
                                  "targetId": "%s",
                                  "comment": "Вёл ровно"
                                }
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isBadRequest());

        verify(service, never()).review(any(), any(), any());
    }

    @Test
    void guestCannotReadPendingReviews() throws Exception {
        mockMvc.perform(get("/api/v1/reviews/pending"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void guestCannotReadReviewTargets() throws Exception {
        mockMvc.perform(get("/api/v1/games/{gameId}/reviews", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void pendingReviewsBelongToTokenSubject() throws Exception {
        UUID userId = UUID.randomUUID();

        mockMvc.perform(get("/api/v1/reviews/pending")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + issueToken(userId)))
                .andExpect(status().isOk());

        verify(service).findPending(userId);
    }

    @Test
    void guestCannotReadPlayerReputation() throws Exception {
        // Репутация игрока — не публичная страница: её читает мастер игры.
        mockMvc.perform(get(
                        "/api/v1/games/{gameId}/players/{playerId}/reputation",
                        UUID.randomUUID(),
                        UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void guestCannotReadPlayerReviews() throws Exception {
        mockMvc.perform(get(
                        "/api/v1/games/{gameId}/players/{playerId}/reviews",
                        UUID.randomUUID(),
                        UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    private String validRequest(UUID targetId) {
        return """
                {
                  "targetId": "%s",
                  "recommended": true,
                  "comment": "Вёл ровно"
                }
                """.formatted(targetId);
    }

    private String issueToken(UUID userId) {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .subject(userId.toString())
                .claim("roles", List.of("USER"))
                .issuedAt(Date.from(Instant.now().minus(1, ChronoUnit.MINUTES)))
                .expiration(Date.from(Instant.now().plus(1, ChronoUnit.HOURS)))
                .signWith(key)
                .compact();
    }
}
