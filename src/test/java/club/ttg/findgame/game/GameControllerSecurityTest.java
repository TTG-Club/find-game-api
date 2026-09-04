package club.ttg.findgame.game;

import club.ttg.findgame.common.ApiExceptionHandler;
import club.ttg.findgame.config.SecurityConfiguration;
import club.ttg.findgame.game.api.GameSearchFilter;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
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
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(GameController.class)
@Import({SecurityConfiguration.class, ApiExceptionHandler.class})
@TestPropertySource(properties = "auth-service.jwt-secret=" + GameControllerSecurityTest.SECRET)
class GameControllerSecurityTest {

    static final String SECRET = "0123456789abcdef0123456789abcdef";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GameService service;

    @Test
    void guestCanSearchPublicGames() throws Exception {
        given(service.findPublic(any(GameSearchFilter.class), anyInt(), anyInt())).willReturn(Page.empty());

        mockMvc.perform(get("/api/v1/games"))
                .andExpect(status().isOk());
    }

    @Test
    void combinesIncludedAndExcludedSearchParameters() throws Exception {
        given(service.findPublic(any(GameSearchFilter.class), anyInt(), anyInt())).willReturn(Page.empty());

        mockMvc.perform(get("/api/v1/games")
                        .param("system", "DND_2024,DND_2014")
                        .param("excludeType", "TEXT")
                        .param("costType", "FREE")
                        .param("minAge", "18")
                        .param("maxAge", "30"))
                .andExpect(status().isOk());

        ArgumentCaptor<GameSearchFilter> captor = ArgumentCaptor.forClass(GameSearchFilter.class);
        verify(service).findPublic(captor.capture(), eq(0), eq(20));
        GameSearchFilter filter = captor.getValue();
        assertThat(filter.systems()).containsExactlyInAnyOrder(GameSystem.DND_2024, GameSystem.DND_2014);
        assertThat(filter.excludedTypes()).containsExactly(GameType.TEXT);
        assertThat(filter.costTypes()).containsExactly(GameCostType.FREE);
        assertThat(filter.minAge()).isEqualTo(18);
        assertThat(filter.maxAge()).isEqualTo(30);
    }

    @Test
    void rejectsInvertedAgeRange() throws Exception {
        mockMvc.perform(get("/api/v1/games")
                        .param("minAge", "30")
                        .param("maxAge", "18"))
                .andExpect(status().isBadRequest());

        verify(service, never()).findPublic(any(), anyInt(), anyInt());
    }

    @Test
    void guestCanReadPublicGameById() throws Exception {
        UUID gameId = UUID.randomUUID();

        mockMvc.perform(get("/api/v1/games/" + gameId))
                .andExpect(status().isOk());

        // Аноним приходит без токена: запросившего нет, кода приглашения тоже.
        verify(service).get(null, gameId, null);
    }

    @Test
    void guestCannotReadOwnGames() throws Exception {
        mockMvc.perform(get("/api/v1/games/my"))
                .andExpect(status().isUnauthorized());

        verify(service, never()).findOwn(any(), any(), anyInt(), anyInt());
    }

    @Test
    void ownGamesUseJwtSubjectAsMaster() throws Exception {
        UUID masterId = UUID.randomUUID();
        given(service.findOwn(any(UUID.class), any(), anyInt(), anyInt())).willReturn(Page.empty());

        mockMvc.perform(get("/api/v1/games/my")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + issueToken(masterId))
                        .param("page", "1")
                        .param("size", "5"))
                .andExpect(status().isOk());

        verify(service).findOwn(masterId, Set.of(), 1, 5);
    }

    /**
     * `/my` — литеральный путь: он не должен уходить в `/{gameId}` и падать на
     * разборе UUID, поэтому проверяем именно вызов `findOwn`, а не `get`.
     */
    @Test
    void ownGamesPathIsNotTreatedAsGameId() throws Exception {
        given(service.findOwn(any(UUID.class), any(), anyInt(), anyInt())).willReturn(Page.empty());

        mockMvc.perform(get("/api/v1/games/my")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + issueToken(UUID.randomUUID())))
                .andExpect(status().isOk());

        verify(service, never()).get(any(), any(), any());
    }

    @Test
    void ownerReadsOwnPrivateGameWithoutInviteCode() throws Exception {
        UUID masterId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();

        mockMvc.perform(get("/api/v1/games/" + gameId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + issueToken(masterId)))
                .andExpect(status().isOk());

        // Владелец узнаётся по токену: кода приглашения у автора своей же
        // приватной игры в адресной строке нет.
        verify(service).get(masterId, gameId, null);
    }

    @Test
    void guestCannotCreateGame() throws Exception {
        mockMvc.perform(post("/api/v1/games")
                        .contentType("application/json")
                        .content(validRequest()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedMasterIdComesFromJwtSubject() throws Exception {
        UUID masterId = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/games")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + issueToken(masterId))
                        .contentType("application/json")
                        .content(validRequest()))
                .andExpect(status().isCreated());

        verify(service).create(eq(masterId), eq("game-master"), any());
    }

    @Test
    void activeGameLimitReturnsConflict() throws Exception {
        given(service.create(any(UUID.class), anyString(), any()))
                .willThrow(new ActiveGameLimitExceededException());

        mockMvc.perform(post("/api/v1/games")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + issueToken(UUID.randomUUID()))
                        .contentType("application/json")
                        .content(validRequest()))
                .andExpect(status().isConflict());
    }

    @Test
    void malformedTokenIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/games")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer garbage")
                        .contentType("application/json")
                        .content(validRequest()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void ownerCanCloseGame() throws Exception {
        UUID masterId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();

        mockMvc.perform(patch("/api/v1/games/{gameId}/close", gameId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + issueToken(masterId)))
                .andExpect(status().isNoContent());

        verify(service).close(masterId, gameId);
    }

    @Test
    void guestCannotEditGame() throws Exception {
        mockMvc.perform(put("/api/v1/games/{gameId}", UUID.randomUUID())
                        .contentType("application/json")
                        .content(validRequest()))
                .andExpect(status().isUnauthorized());

        verify(service, never()).update(any(), any(), any(), any());
    }

    @Test
    void editUsesJwtSubjectAsMaster() throws Exception {
        UUID masterId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();

        mockMvc.perform(put("/api/v1/games/{gameId}", gameId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + issueToken(masterId))
                        .contentType("application/json")
                        .content(validRequest()))
                .andExpect(status().isOk());

        // Владельца берём только из токена: подменить его телом запроса нельзя.
        verify(service).update(eq(masterId), any(), eq(gameId), any());
    }

    @Test
    void editRejectsInvalidBody() throws Exception {
        mockMvc.perform(put("/api/v1/games/{gameId}", UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + issueToken(UUID.randomUUID()))
                        .contentType("application/json")
                        .content("""
                                {"title":"","system":"DND_2024"}
                                """))
                .andExpect(status().isBadRequest());

        verify(service, never()).update(any(), any(), any(), any());
    }

    @Test
    void editByStrangerIsForbidden() throws Exception {
        given(service.update(any(), any(), any(), any()))
                .willThrow(new GameAccessDeniedException());

        mockMvc.perform(put("/api/v1/games/{gameId}", UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + issueToken(UUID.randomUUID()))
                        .contentType("application/json")
                        .content(validRequest()))
                .andExpect(status().isForbidden());
    }

    @Test
    void guestCannotCloseGame() throws Exception {
        mockMvc.perform(patch("/api/v1/games/{gameId}/close", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void ownerCanRaiseGame() throws Exception {
        UUID masterId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();

        mockMvc.perform(patch("/api/v1/games/{gameId}/raise", gameId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + issueToken(masterId)))
                .andExpect(status().isOk());

        verify(service).raise(masterId, "game-master", gameId);
    }

    @Test
    void guestCannotRaiseGame() throws Exception {
        mockMvc.perform(patch("/api/v1/games/{gameId}/raise", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void raiseCooldownReturnsTooManyRequests() throws Exception {
        UUID gameId = UUID.randomUUID();
        Instant availableAt = Instant.now().plus(1, ChronoUnit.HOURS);
        given(service.raise(any(UUID.class), anyString(), eq(gameId)))
                .willThrow(new GameRaiseCooldownException(availableAt));

        mockMvc.perform(patch("/api/v1/games/{gameId}/raise", gameId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + issueToken(UUID.randomUUID())))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void guestCannotDeleteGame() throws Exception {
        mockMvc.perform(delete("/api/v1/games/{gameId}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void regularUserCannotDeleteGame() throws Exception {
        mockMvc.perform(delete("/api/v1/games/{gameId}", UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION,
                                "Bearer " + issueToken(UUID.randomUUID(), "USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void administratorCanDeleteGame() throws Exception {
        UUID gameId = UUID.randomUUID();

        mockMvc.perform(delete("/api/v1/games/{gameId}", gameId)
                        .header(HttpHeaders.AUTHORIZATION,
                                "Bearer " + issueToken(UUID.randomUUID(), "ADMIN"))
                        .contentType("application/json")
                        .content("""
                                {"reason":"Нарушение правил"}
                                """))
                .andExpect(status().isNoContent());

        verify(service).delete(gameId, "Нарушение правил");
    }

    @Test
    void moderatorCanDeleteGame() throws Exception {
        UUID gameId = UUID.randomUUID();

        mockMvc.perform(delete("/api/v1/games/{gameId}", gameId)
                        .header(HttpHeaders.AUTHORIZATION,
                                "Bearer " + issueToken(UUID.randomUUID(), "MODERATOR")))
                .andExpect(status().isNoContent());

        verify(service).delete(gameId, null);
    }

    @Test
    void deletionReasonCannotBeBlank() throws Exception {
        UUID gameId = UUID.randomUUID();

        mockMvc.perform(delete("/api/v1/games/{gameId}", gameId)
                        .header(HttpHeaders.AUTHORIZATION,
                                "Bearer " + issueToken(UUID.randomUUID(), "ADMIN"))
                        .contentType("application/json")
                        .content("""
                                {"reason":"   "}
                                """))
                .andExpect(status().isBadRequest());

        verify(service, never()).delete(eq(gameId), any());
    }

    private String validRequest() {
        return """
                {
                  "title": "Проклятие Страда",
                  "system": "DND_2024",
                  "description": "Готическая кампания",
                  "requirements": "Стабильное участие",
                  "type": "ONLINE",
                  "playersToStart": 3,
                  "maxPlayers": 5,
                  "startingLevel": 1,
                  "durationType": "CAMPAIGN",
                  "costType": "FREE",
                  "visibility": "PUBLIC"
                }
                """;
    }

    private String issueToken(UUID userId) {
        return issueToken(userId, "USER");
    }

    private String issueToken(UUID userId, String role) {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .subject(userId.toString())
                .claim("username", "game-master")
                .claim("roles", List.of(role))
                .issuedAt(Date.from(Instant.now().minus(1, ChronoUnit.MINUTES)))
                .expiration(Date.from(Instant.now().plus(1, ChronoUnit.HOURS)))
                .signWith(key)
                .compact();
    }
}
