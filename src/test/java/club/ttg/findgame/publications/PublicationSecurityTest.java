package club.ttg.findgame.publications;

import club.ttg.findgame.config.SecurityConfiguration;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Проверяет защиту чтения, изменения и предпросмотра настоящей JWT-цепочкой. */
@WebMvcTest(PublicationController.class)
@Import(SecurityConfiguration.class)
@TestPropertySource(properties = "auth-service.jwt-secret=" + PublicationSecurityTest.SECRET)
class PublicationSecurityTest {
    static final String SECRET = "0123456789abcdef0123456789abcdef";
    static final String PATH = "/api/v1/admin/discord-publications";
    @Autowired MockMvc mvc;
    @MockitoBean PublicationService service;
    @MockitoBean GameDigest digest;
    @MockitoBean PublicationTestSender testSender;

    @Test void guestCannotReadOrChange() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mvc.perform(post(PATH + "/channels").contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
        mvc.perform(post(PATH + "/channels/" + UUID.randomUUID() + "/test").param("revision", "0")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service, digest, testSender);
    }
    @ParameterizedTest @ValueSource(strings = {"USER", "MODERATOR"})
    void nonAdminCannotReadOrChange(String role) throws Exception {
        mvc.perform(get(PATH + "/preview").header("Authorization", token(role))).andExpect(status().isForbidden());
        mvc.perform(put(PATH + "/settings").header("Authorization", token(role)).contentType("application/json").content("{}")).andExpect(status().isForbidden());
        mvc.perform(delete(PATH + "/channels/" + UUID.randomUUID()).param("revision", "0").header("Authorization", token(role))).andExpect(status().isForbidden());
        mvc.perform(post(PATH + "/channels/" + UUID.randomUUID() + "/test").param("revision", "0").header("Authorization", token(role))).andExpect(status().isForbidden());
        verifyNoInteractions(service, digest, testSender);
    }
    @Test void adminCanReadAndMalformedSchedulesAreRejected() throws Exception {
        when(digest.preview()).thenReturn(List.of());
        mvc.perform(get(PATH + "/preview").header("Authorization", token("ADMIN"))).andExpect(status().isOk()).andExpect(content().json("[]"));
        for (String schedule : List.of("[{\"day\":0,\"time\":\"12:00\"}]", "[{\"day\":1,\"time\":\"25:00\"}]", "[null]")) {
            mvc.perform(put(PATH + "/settings").header("Authorization", token("ADMIN")).contentType("application/json")
                    .content("{\"enabled\":true,\"revision\":0,\"schedule\":" + schedule + "}"))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }
    /** ADMIN запускает только сохранённый канал с обязательной версией; ответ не содержит секрета. */
    @Test void adminCanSendTestWithRequiredRevision() throws Exception {
        UUID channelId = UUID.randomUUID();
        when(testSender.send(channelId, 3)).thenReturn(new PublicationModels.TestResult("SENT", "Опубликовано"));
        mvc.perform(post(PATH + "/channels/" + channelId + "/test").header("Authorization", token("ADMIN")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(testSender);
        mvc.perform(post(PATH + "/channels/" + channelId + "/test").param("revision", "3").header("Authorization", token("ADMIN")))
                .andExpect(status().isOk()).andExpect(content().json("{\"status\":\"SENT\",\"detail\":\"Опубликовано\"}"))
                .andExpect(jsonPath("$.webhookUrl").doesNotExist());
        verify(testSender).send(channelId, 3);
    }

    /** Новый адрес имеет ту же проверку ролей; контракт Telegram не раскрывает сохранённый ID. */
    @Test void canonicalRouteProtectsTelegramSettings() throws Exception {
        String path = "/api/v1/admin/game-publications";
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        for (String role : List.of("USER", "MODERATOR")) {
            mvc.perform(get(path).header("Authorization", token(role))).andExpect(status().isForbidden());
            mvc.perform(post(path + "/channels").header("Authorization", token(role))
                    .contentType("application/json").content("{}"))
                    .andExpect(status().isForbidden());
            mvc.perform(post(path + "/channels/" + UUID.randomUUID() + "/test").param("revision", "0")
                    .header("Authorization", token(role))).andExpect(status().isForbidden());
        }
        verifyNoInteractions(service, digest, testSender);
        PublicationModels.Channel channel = new PublicationModels.Channel(UUID.randomUUID(), "Telegram", true, null, 0, null, PublicationModels.Platform.TELEGRAM, null);
        PublicationModels.Overview overview = new PublicationModels.Overview(new PublicationModels.Settings(false, List.of(), 0, null), List.of(channel), true, "Europe/Moscow", true, false, false);
        when(service.saveChannel(isNull(), any())).thenReturn(overview);
        mvc.perform(post(path + "/channels").header("Authorization", token("ADMIN"))
                .contentType("application/json").content("""
                        {"name":"Telegram","enabled":true,"revision":0,"platform":"TELEGRAM","telegramChatId":"-1001234567890"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channels[0].platform").value("TELEGRAM"))
                .andExpect(jsonPath("$.channels[0].telegramChatId").doesNotExist())
                .andExpect(jsonPath("$.telegramConfigured").value(true));
        verify(service).saveChannel(isNull(), argThat(input -> input.platform() == PublicationModels.Platform.TELEGRAM
                && input.telegramChatId().equals("-1001234567890")));
    }

    /** Канал ВКонтакте принимается только от ADMIN; ID сообщества не возвращается, путь картинки — возвращается. */
    @Test void vkChannelKeepsGroupIdPrivate() throws Exception {
        String path = "/api/v1/admin/game-publications";
        String image = "/s3/game-publications/admin/1758560000000-cover.webp";
        String body = """
                {"name":"ВКонтакте","enabled":true,"revision":0,"platform":"VK","vkGroupId":"212345678","imageUrl":"%s"}
                """.formatted(image);
        mvc.perform(post(path + "/channels").header("Authorization", token("USER")).contentType("application/json").content(body))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
        PublicationModels.Channel channel = new PublicationModels.Channel(UUID.randomUUID(), "ВКонтакте", true, null, 0, null, PublicationModels.Platform.VK, image);
        PublicationModels.Overview overview = new PublicationModels.Overview(new PublicationModels.Settings(false, List.of(), 0, null), List.of(channel), true, "Europe/Moscow", false, true, true);
        when(service.saveChannel(isNull(), any())).thenReturn(overview);
        mvc.perform(post(path + "/channels").header("Authorization", token("ADMIN")).contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channels[0].platform").value("VK"))
                .andExpect(jsonPath("$.channels[0].vkGroupId").doesNotExist())
                .andExpect(jsonPath("$.channels[0].imageUrl").value(image))
                .andExpect(jsonPath("$.vkConfigured").value(true));
        verify(service).saveChannel(isNull(), argThat(input -> input.platform() == PublicationModels.Platform.VK
                && input.vkGroupId().equals("212345678") && input.imageUrl().equals(image)));
    }

    /** Создаёт подписанную сессию с указанной ролью. */
    private String token(String role) {
        return "Bearer " + Jwts.builder().subject(UUID.randomUUID().toString()).claim("roles", List.of(role))
                .issuedAt(Date.from(Instant.now().minusSeconds(60))).expiration(Date.from(Instant.now().plusSeconds(3600)))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
    }
}
