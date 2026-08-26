package club.ttg.findgame.chat;

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
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ChatController.class)
@Import({SecurityConfiguration.class, ApiExceptionHandler.class})
@TestPropertySource(properties = "auth-service.jwt-secret=" + ChatControllerSecurityTest.SECRET)
class ChatControllerSecurityTest {

    static final String SECRET = "0123456789abcdef0123456789abcdef";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChatService service;

    @Test
    void guestCannotSendChatEvent() throws Exception {
        mockMvc.perform(post("/api/v1/games/{gameId}/chat/events", UUID.randomUUID())
                        .contentType("application/json")
                        .content(textRequest()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedUserIdComesFromJwtSubject() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/games/{gameId}/sessions/{sessionId}/chat/events", gameId, sessionId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + issueToken(userId))
                        .contentType("application/json")
                        .content(textRequest()))
                .andExpect(status().isCreated());

        verify(service).create(eq(userId), eq(gameId), eq(sessionId), any());
    }

    private String textRequest() {
        return """
                {
                  "clientMessageId": "%s",
                  "type": "TEXT",
                  "text": "Всем привет!"
                }
                """.formatted(UUID.randomUUID());
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
