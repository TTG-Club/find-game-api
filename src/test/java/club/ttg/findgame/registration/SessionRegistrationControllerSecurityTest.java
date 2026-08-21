package club.ttg.findgame.registration;

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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SessionRegistrationController.class)
@Import({SecurityConfiguration.class, ApiExceptionHandler.class})
@TestPropertySource(properties = "auth-service.jwt-secret=" + SessionRegistrationControllerSecurityTest.SECRET)
class SessionRegistrationControllerSecurityTest {

    static final String SECRET = "0123456789abcdef0123456789abcdef";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SessionRegistrationService service;

    @Test
    void guestCannotRegister() throws Exception {
        mockMvc.perform(post(url(), UUID.randomUUID(), UUID.randomUUID())
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void playerIdComesFromJwtSubject() throws Exception {
        UUID playerId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();

        mockMvc.perform(post(url(), gameId, sessionId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + issueToken(playerId))
                        .contentType("application/json")
                        .content("{\"characterSheetUrl\":\"https://ttg.club/characters/1\"}"))
                .andExpect(status().isCreated());

        verify(service).register(eq(playerId), eq(gameId), eq(sessionId), isNull(), any());
    }

    @Test
    void playerChangesAttendanceUsingJwtSubject() throws Exception {
        UUID playerId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();

        mockMvc.perform(patch(url() + "/me/attendance", gameId, sessionId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + issueToken(playerId))
                        .contentType("application/json")
                        .content("{\"attendanceStatus\":\"ATTENDING\"}"))
                .andExpect(status().isOk());

        verify(service).updateAttendance(eq(playerId), eq(gameId), eq(sessionId), any());
    }

    @Test
    void masterUpdatesPaymentUsingJwtSubject() throws Exception {
        UUID masterId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID registrationId = UUID.randomUUID();

        mockMvc.perform(patch(url() + "/{registrationId}/payment", gameId, sessionId, registrationId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + issueToken(masterId))
                        .contentType("application/json")
                        .content("{\"paid\":true}"))
                .andExpect(status().isOk());

        verify(service).updatePaymentStatus(
                eq(masterId), eq(gameId), eq(sessionId), eq(registrationId), any());
    }

    private String url() {
        return "/api/v1/games/{gameId}/sessions/{sessionId}/registrations";
    }

    private String issueToken(UUID userId) {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .subject(userId.toString())
                .issuedAt(Date.from(Instant.now().minus(1, ChronoUnit.MINUTES)))
                .expiration(Date.from(Instant.now().plus(1, ChronoUnit.HOURS)))
                .signWith(key)
                .compact();
    }
}
