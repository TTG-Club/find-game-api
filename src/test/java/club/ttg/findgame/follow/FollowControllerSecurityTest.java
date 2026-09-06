package club.ttg.findgame.follow;

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

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FollowController.class)
@Import({SecurityConfiguration.class, ApiExceptionHandler.class})
@TestPropertySource(properties = "auth-service.jwt-secret=" + FollowControllerSecurityTest.SECRET)
class FollowControllerSecurityTest {

    static final String SECRET = "0123456789abcdef0123456789abcdef";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FollowService service;

    @Test
    void guestDoesNotMarkMasters() throws Exception {
        // Отметка живёт в списке пользователя — гостю его негде хранить.
        mockMvc.perform(put("/api/v1/profiles/masters/{masterId}/follow", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void ownerComesFromJwtSubject() throws Exception {
        UUID playerId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();

        mockMvc.perform(put("/api/v1/profiles/masters/{masterId}/follow", masterId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + issueToken(playerId)))
                .andExpect(status().isNoContent());

        verify(service).followMaster(playerId, masterId);
    }

    @Test
    void markIsRemovedByItsOwner() throws Exception {
        UUID masterId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();

        mockMvc.perform(delete("/api/v1/profiles/players/{playerId}/bookmark", playerId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + issueToken(masterId)))
                .andExpect(status().isNoContent());

        verify(service).unbookmarkPlayer(masterId, playerId);
    }

    @Test
    void guestDoesNotReadSomebodysList() throws Exception {
        mockMvc.perform(get("/api/v1/profiles/me/follows/masters"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void guestDoesNotInvite() throws Exception {
        mockMvc.perform(post("/api/v1/games/{gameId}/invites", UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"playerId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void inviteNeedsAPlayer() throws Exception {
        UUID masterId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/games/{gameId}/invites", gameId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + issueToken(masterId))
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest());

        verify(service, never()).invitePlayer(masterId, gameId, null);
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
