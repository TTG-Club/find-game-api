package club.ttg.findgame.registration;

import club.ttg.findgame.common.ApiExceptionHandler;
import club.ttg.findgame.config.SecurityConfiguration;
import club.ttg.findgame.registration.api.GameParticipantResponse;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(GameRegistrationController.class)
@Import({SecurityConfiguration.class, ApiExceptionHandler.class})
@TestPropertySource(properties = "auth-service.jwt-secret=0123456789abcdef0123456789abcdef")
class GameRegistrationControllerSecurityTest {
    @Autowired private MockMvc mockMvc;
    @MockitoBean private GameRegistrationService service;

    @Test
    void guestCannotReadParticipants() throws Exception {
        mockMvc.perform(get("/api/v1/games/{gameId}/registrations/participants", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void participantsUseJwtIdentityAndMinimalResponse() throws Exception {
        UUID playerId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        when(service.findParticipants(playerId, gameId)).thenReturn(List.of(new GameParticipantResponse(playerId, "Следопыт", null)));
        mockMvc.perform(get("/api/v1/games/{gameId}/registrations/participants", gameId)
                        .header("Authorization", "Bearer " + token(playerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].playerId").value(playerId.toString()))
                .andExpect(jsonPath("$[0].characterSheetUrl").doesNotExist())
                .andExpect(jsonPath("$[0].rejectionReason").doesNotExist());
        verify(service).findParticipants(playerId, gameId);
    }

    @Test
    void exitUsesOnlyJwtIdentity() throws Exception {
        UUID playerId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        mockMvc.perform(delete("/api/v1/games/{gameId}/registrations/me", gameId)
                        .header("Authorization", "Bearer " + token(playerId)))
                .andExpect(status().isNoContent());
        verify(service).withdraw(playerId, gameId);
    }

    /** Создаёт токен тестового игрока. */
    private static String token(UUID playerId) {
        return Jwts.builder().subject(playerId.toString()).claim("roles", List.of("USER"))
                .expiration(Date.from(Instant.now().plusSeconds(3600)))
                .signWith(Keys.hmacShaKeyFor("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)))
                .compact();
    }
}
