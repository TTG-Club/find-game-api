package club.ttg.findgame.discord;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static club.ttg.findgame.discord.PublicationModels.*;

/** Проверяет границы расписания, безопасность вебхука и полный путь отправки. */
class PublicationRulesTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void nextWeeklySlotUsesMoscowAndStrictBoundary() {
        List<Slot> slots = List.of(new Slot(1, "18:00"), new Slot(4, "20:00"));
        assertThat(WeeklySchedule.next(slots, Instant.parse("2026-09-14T14:59:59Z"))).isEqualTo("2026-09-14T15:00:00Z");
        assertThat(WeeklySchedule.next(slots, Instant.parse("2026-09-14T15:00:00Z"))).isEqualTo("2026-09-17T17:00:00Z");
        assertThat(WeeklySchedule.next(slots, Instant.parse("2026-09-17T17:00:00Z"))).isEqualTo("2026-09-21T15:00:00Z");
        assertThatThrownBy(() -> WeeklySchedule.validate(List.of(slots.getFirst(), slots.getFirst()), true)).isInstanceOf(RuntimeException.class);
    }

    @Test void webhookAllowsOnlyDiscordAndAuthenticatesCiphertext() {
        WebhookSecrets secrets = new WebhookSecrets(Base64.getEncoder().encodeToString(new byte[32]), "0123456789abcdef0123456789abcdef");
        String valid = "https://discord.com/api/webhooks/123456789012345678/" + "a".repeat(60);
        for (String invalid : List.of(valid + "?wait=false", valid + "#fragment", valid.replace("discord.com", "discord.com.attacker.test"), valid.replace("https:", "http:"), valid.replace("discord.com", "127.0.0.1"))) {
            assertThatThrownBy(() -> secrets.normalize(invalid)).isInstanceOf(RuntimeException.class);
        }
        String encrypted = secrets.encrypt(valid);
        assertThat(secrets.encrypt(valid)).isNotEqualTo(encrypted);
        assertThatThrownBy(() -> secrets.decrypt(encrypted.substring(1))).isInstanceOf(IllegalStateException.class);
        assertThat(new WebhookSecrets("", "0123456789abcdef0123456789abcdef").configured()).isTrue();
    }

    @Test void discordAcknowledgementsAndRateLimit() {
        DiscordWebhookClient client = new DiscordWebhookClient(mapper);
        Instant now = Instant.parse("2026-09-17T10:00:00Z");
        assertThat(client.interpret(200, "{\"id\":\"123456789012345678\"}", now).status()).isEqualTo("SENT");
        assertThat(client.interpret(200, "{}", now).status()).isEqualTo("UNKNOWN");
        assertThat(client.interpret(500, "error", now).status()).isEqualTo("UNKNOWN");
        assertThat(client.interpret(404, "secret response", now).detail()).doesNotContain("secret response");
        assertThat(client.interpret(429, "{\"retry_after\":1.5}", now).retryAt()).isEqualTo(now.plusMillis(2500));
        assertThat(client.interpret(429, "{}", now).status()).isEqualTo("FAILED");
    }

    @Test void sendsCompactPayloadAndRecordsResult() {
        PublicationService service = mock(PublicationService.class);
        WebhookSecrets secrets = mock(WebhookSecrets.class);
        GameDigest digest = mock(GameDigest.class);
        DiscordWebhookClient client = mock(DiscordWebhookClient.class);
        PublicationScheduler scheduler = new PublicationScheduler(service, secrets, digest, client);
        Delivery delivery = new Delivery(UUID.randomUUID(), UUID.randomUUID(), 0, "encrypted", Instant.now(), 1);
        GameEntry game = new GameEntry(UUID.randomUUID(), "Название", "D&D", 1, 4, "https://ttg.club/games/example");
        List<GameEntry> games = List.of(game);
        Map<String, Object> payload = Map.of("embeds", List.of());
        when(digest.preview()).thenReturn(games);
        when(digest.payload(games)).thenReturn(payload);
        when(service.current(delivery)).thenReturn(true);
        when(secrets.decrypt("encrypted")).thenReturn("validated-webhook");
        Outcome sent = new Outcome("SENT", "Опубликовано", "123456789012345678", null);
        when(client.send("validated-webhook", payload)).thenReturn(sent);
        scheduler.publish(delivery);
        verify(service).finish(eq(delivery), eq(sent), eq(1), any());
        verify(client).send("validated-webhook", payload);
        when(service.current(delivery)).thenReturn(false);
        scheduler.publish(delivery);
        verifyNoMoreInteractions(client);
    }

    @Test void maximumPayloadFitsAndDisablesMentions() {
        String siteUrl = "https://" + "a".repeat(56) + ".org";
        GameDigest digest = new GameDigest(mock(org.springframework.jdbc.core.JdbcTemplate.class), siteUrl);
        List<GameEntry> games = new ArrayList<>();
        for (int index = 0; index < 20; index++) games.add(new GameEntry(UUID.randomUUID(), "*<@everyone>🎲".repeat(30), "[system]".repeat(20), Integer.MAX_VALUE - 1, Integer.MAX_VALUE, siteUrl + "/games/" + UUID.randomUUID()));
        var payload = mapper.valueToTree(digest.payload(games));
        var embed = payload.path("embeds").get(0);
        int characters = embed.path("title").asText().length();
        for (var field : embed.path("fields")) characters += field.path("name").asText().length() + field.path("value").asText().length();
        assertThat(characters).isLessThanOrEqualTo(6000);
        assertThat(embed.path("fields").size()).isEqualTo(20);
        assertThat(payload.path("allowed_mentions").path("parse").isEmpty()).isTrue();
    }

    @Test void emptyCatalogueNeverCallsDiscord() {
        PublicationService service = mock(PublicationService.class);
        WebhookSecrets secrets = mock(WebhookSecrets.class);
        GameDigest digest = mock(GameDigest.class);
        DiscordWebhookClient client = mock(DiscordWebhookClient.class);
        Delivery delivery = new Delivery(UUID.randomUUID(), UUID.randomUUID(), 0, "encrypted", Instant.now(), 1);
        when(digest.preview()).thenReturn(List.of());
        when(service.current(delivery)).thenReturn(true);
        new PublicationScheduler(service, secrets, digest, client).publish(delivery);
        verifyNoInteractions(client, secrets);
        verify(service).finish(eq(delivery), eq(new Outcome("SKIPPED", "Нет игр с открытым набором", null, null)), eq(0), any());
    }
}
