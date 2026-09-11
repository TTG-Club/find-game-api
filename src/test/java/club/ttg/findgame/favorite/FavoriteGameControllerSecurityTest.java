package club.ttg.findgame.favorite;

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
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FavoriteGameController.class)
@Import({SecurityConfiguration.class, ApiExceptionHandler.class})
@TestPropertySource(properties = "auth-service.jwt-secret=" + FavoriteGameControllerSecurityTest.SECRET)
class FavoriteGameControllerSecurityTest {

    static final String SECRET = "0123456789abcdef0123456789abcdef";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FavoriteGameService service;

    @Test
    void guestHasNoFavorites() throws Exception {
        // Список живёт у пользователя — гостю его негде хранить.
        mockMvc.perform(put("/api/v1/games/{gameId}/favorite", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/v1/games/{gameId}/favorite", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/profiles/me/favorites/games"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void ownerComesFromJwtSubject() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();

        mockMvc.perform(put("/api/v1/games/{gameId}/favorite", gameId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + issueToken(ownerId)))
                .andExpect(status().isNoContent());

        verify(service).addFavorite(ownerId, gameId);
    }

    @Test
    void markIsRemovedByItsOwner() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();

        mockMvc.perform(delete("/api/v1/games/{gameId}/favorite", gameId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + issueToken(ownerId)))
                .andExpect(status().isNoContent());

        verify(service).removeFavorite(ownerId, gameId);
    }

    private static String issueToken(UUID userId) {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();

        return Jwts.builder()
                .subject(userId.toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(1, ChronoUnit.HOURS)))
                .signWith(key)
                .compact();
    }
}
