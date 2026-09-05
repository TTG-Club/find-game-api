package club.ttg.findgame.profile;

import club.ttg.findgame.common.ApiExceptionHandler;
import club.ttg.findgame.config.SecurityConfiguration;
import club.ttg.findgame.review.SessionReviewService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserProfileController.class)
@Import({SecurityConfiguration.class, ApiExceptionHandler.class})
@TestPropertySource(properties = "auth-service.jwt-secret=" + UserProfileControllerSecurityTest.SECRET)
class UserProfileControllerSecurityTest {

    static final String SECRET = "0123456789abcdef0123456789abcdef";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserProfileService service;

    @MockitoBean
    private MasterProfileService masterService;

    @MockitoBean
    private SessionReviewService reviewService;

    @Test
    void guestReadsMasterProfile() throws Exception {
        UUID masterId = UUID.randomUUID();

        // Каталог открыт и гостю, а имя мастера стоит в каждой карточке.
        mockMvc.perform(get("/api/v1/profiles/masters/" + masterId))
                .andExpect(status().isOk());

        verify(masterService).get(masterId);
    }

    @Test
    void guestReadsMasterReviews() throws Exception {
        UUID masterId = UUID.randomUUID();

        // Отзывы о мастере читают до заявки — иначе они ни на что не влияют.
        mockMvc.perform(get("/api/v1/profiles/masters/" + masterId + "/reviews"))
                .andExpect(status().isOk());

        verify(reviewService).findMasterReviews(masterId);
    }

    @Test
    void guestCannotReadOwnReputation() throws Exception {
        mockMvc.perform(get("/api/v1/profiles/me/reputation"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void guestCannotReadProfile() throws Exception {
        mockMvc.perform(get("/api/v1/profiles/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void userIdComesFromJwtWhenReadingProfile() throws Exception {
        UUID userId = UUID.randomUUID();

        mockMvc.perform(get("/api/v1/profiles/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + issueToken(userId)))
                .andExpect(status().isOk());

        verify(service).getOrCreate(userId);
    }

    @Test
    void userCanUpdateOwnTwoProfiles() throws Exception {
        UUID userId = UUID.randomUUID();

        mockMvc.perform(put("/api/v1/profiles/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + issueToken(userId))
                        .contentType("application/json")
                        .content("""
                                {
                                  "birthYear": 1990,
                                  "gender": "MALE",
                                  "tabletopExperienceYears": 7,
                                  "master": {"about": "Вожу кампании"},
                                  "player": {"about": "Люблю исследование"}
                                }
                                """))
                .andExpect(status().isOk());

        verify(service).update(eq(userId), any());
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
