package club.ttg.findgame.session;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(GameSessionController.class)
@Import({SecurityConfiguration.class, ApiExceptionHandler.class})
@TestPropertySource(properties = "auth-service.jwt-secret=" + GameSessionControllerSecurityTest.SECRET)
class GameSessionControllerSecurityTest {

    static final String SECRET = "0123456789abcdef0123456789abcdef";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GameSessionService service;

    @Test
    void guestCannotCreateSession() throws Exception {
        mockMvc.perform(post("/api/v1/games/{gameId}/sessions", UUID.randomUUID())
                        .contentType("application/json")
                        .content(validRequest()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedMasterIdComesFromJwtSubject() throws Exception {
        UUID masterId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/games/{gameId}/sessions", gameId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + issueToken(masterId))
                        .contentType("application/json")
                        .content(validRequest()))
                .andExpect(status().isCreated());

        verify(service).create(eq(masterId), eq(gameId), any());
    }

    @Test
    void authenticatedMasterCanCopySessionUsingJwtSubject() throws Exception {
        UUID masterId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        UUID sourceSessionId = UUID.randomUUID();

        mockMvc.perform(post(
                        "/api/v1/games/{gameId}/sessions/{sourceSessionId}/copy",
                        gameId,
                        sourceSessionId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + issueToken(masterId))
                        .contentType("application/json")
                        .content("{\"startsAt\":\"2099-01-17T18:00:00Z\"}"))
                .andExpect(status().isCreated());

        verify(service).copy(eq(masterId), eq(gameId), eq(sourceSessionId), any());
    }

    @Test
    void estimatedDurationMustBePositive() throws Exception {
        UUID masterId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/games/{gameId}/sessions", gameId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + issueToken(masterId))
                        .contentType("application/json")
                        .content("""
                                {
                                  "title": "Первая глава",
                                  "startsAt": "2099-01-10T18:00:00Z",
                                  "estimatedDurationMinutes": 0
                                }
                                """))
                .andExpect(status().isBadRequest());

        verify(service, never()).create(eq(masterId), eq(gameId), any());
    }

    private String validRequest() {
        return """
                {
                  "title": "Первая глава",
                  "startsAt": "2099-01-10T18:00:00Z",
                  "estimatedDurationMinutes": 240
                }
                """;
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
