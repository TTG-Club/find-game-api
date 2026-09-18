package club.ttg.findgame.publications;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static club.ttg.findgame.publications.PublicationModels.*;

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
        PublicationSecrets secrets = new PublicationSecrets(Base64.getEncoder().encodeToString(new byte[32]), "0123456789abcdef0123456789abcdef");
        String valid = "https://discord.com/api/webhooks/123456789012345678/" + "a".repeat(60);
        for (String invalid : List.of(valid + "?wait=false", valid + "#fragment", valid.replace("discord.com", "discord.com.attacker.test"), valid.replace("https:", "http:"), valid.replace("discord.com", "127.0.0.1"))) {
            assertThatThrownBy(() -> secrets.normalize(Platform.DISCORD, invalid)).isInstanceOf(RuntimeException.class);
        }
        String encrypted = secrets.encrypt(Platform.DISCORD, valid);
        assertThat(secrets.encrypt(Platform.DISCORD, valid)).isNotEqualTo(encrypted);
        assertThatThrownBy(() -> secrets.decrypt(Platform.DISCORD, encrypted.substring(1))).isInstanceOf(IllegalStateException.class);
        assertThat(new PublicationSecrets("", "0123456789abcdef0123456789abcdef").configured()).isTrue();
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
        PublicationSecrets secrets = mock(PublicationSecrets.class);
        GameDigest digest = mock(GameDigest.class);
        DiscordWebhookClient client = mock(DiscordWebhookClient.class);
        PublicationScheduler scheduler = new PublicationScheduler(service, digest, new PublicationSender(secrets, client, mock(TelegramBotClient.class)));
        Delivery delivery = new Delivery(UUID.randomUUID(), UUID.randomUUID(), 0, "encrypted", Instant.now(), 1, Platform.DISCORD);
        GameEntry game = new GameEntry(UUID.randomUUID(), "Название", "D&D", 1, 4, "https://new.ttg.club/games/example", "Фэнтези", "Короткое описание");
        List<GameEntry> games = List.of(game);
        Map<String, Object> payload = Map.of("content", "Подборка");
        when(digest.preview()).thenReturn(games);
        when(digest.messages(games, Platform.DISCORD)).thenReturn(List.of(new DigestMessage(payload, 1)));
        when(service.current(delivery)).thenReturn(true);
        when(secrets.decrypt(Platform.DISCORD, "encrypted")).thenReturn("validated-webhook");
        Outcome sent = new Outcome("SENT", "Опубликовано", "123456789012345678", null);
        when(client.send("validated-webhook", payload)).thenReturn(sent);
        scheduler.publish(delivery);
        verify(service).finish(eq(delivery), argThat(outcome -> outcome.status().equals("SENT") && outcome.messageId().equals(sent.messageId())), eq(1), any());
        verify(client).send("validated-webhook", payload);
        when(service.current(delivery)).thenReturn(false);
        scheduler.publish(delivery);
        verifyNoMoreInteractions(client);
    }

    @Test void maximumPayloadFitsAndDisablesMentions() {
        String siteUrl = "https://" + "a".repeat(56) + ".org";
        GameDigest digest = new GameDigest(mock(JdbcTemplate.class), siteUrl);
        List<GameEntry> games = new ArrayList<>();
        for (int index = 0; index < 20; index++) games.add(new GameEntry(UUID.randomUUID(), "*<@everyone>🎲".repeat(30), "[system]".repeat(20), Integer.MAX_VALUE - 1, Integer.MAX_VALUE, siteUrl + "/games/" + UUID.randomUUID(), "Жанр".repeat(100), "Описание 🎲 ".repeat(50) + "."));
        List<DigestMessage> messages = digest.messages(games, Platform.DISCORD);
        assertThat(messages).hasSize(1);
        assertThat(messages.getFirst().gameCount()).isEqualTo(8);
        String joined = messages.stream().map(message -> message.payload().get("content").toString()).collect(java.util.stream.Collectors.joining("\n"));
        for (GameEntry game : games.subList(0, 8)) assertThat(joined).containsOnlyOnce(game.url());
        for (GameEntry game : games.subList(8, 20)) assertThat(joined).doesNotContain(game.url());
        for (DigestMessage message : messages) {
            var payload = mapper.valueToTree(message.payload());
            String content = payload.path("content").asString();
            assertThat(content.length()).isLessThanOrEqualTo(2000);
            assertThat(content).startsWith("Ищете компанию для приключений? 🎲\n\n")
                    .contains("игры с открытым набором на " + siteUrl.substring("https://".length()));
            assertThat(content.substring(content.lastIndexOf("\n\n")))
                    .startsWith("\n\nХотите больше вариантов? ")
                    .containsOnlyOnce("[полный список игр на сайте](" + siteUrl + "/games)")
                    .endsWith("Будем рады каждому!");
            assertThat(payload.has("embeds")).isFalse();
            assertThat(payload.path("flags").asInt()).isEqualTo(4);
            assertThat(payload.path("allowed_mentions").path("parse").isEmpty()).isTrue();
            assertThat(content).doesNotContain("\n\n[Подробнее", "\n> ", "Подробнее на сайте");
            assertThat(content.lines().filter(line -> line.startsWith("Жанры: ")).count()).isEqualTo(8);
        }
        assertThat(digest.messages(List.of(), Platform.DISCORD)).isEmpty();
    }

    /** Даже короткие описания не публикуются, когда в сообщении для них достаточно места. */
    @Test void eightGamesNeverPublishDescriptions() {
        GameDigest digest = new GameDigest(mock(JdbcTemplate.class), "https://new.ttg.club");
        List<GameEntry> games = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            UUID gameId = UUID.randomUUID();
            games.add(new GameEntry(gameId, "Игра " + index, "D&D", 1, 4,
                    "https://new.ttg.club/games/" + gameId, "Детектив", "Найдите мага…"));
        }
        List<DigestMessage> messages = digest.messages(games, Platform.DISCORD);
        assertThat(messages).hasSize(1);
        String content = messages.getFirst().payload().get("content").toString();
        assertThat(content.length()).isLessThanOrEqualTo(2000);
        assertThat(messages.getFirst().gameCount()).isEqualTo(8);
        assertThat(content).contains("\nЖанры: Детектив\n[Подробнее](")
                .doesNotContain("Найдите мага", "\n> ", "\n\n[Подробнее");
    }

    /** Пробелы и эмодзи возле границы сокращения не позволяют превысить общий лимит. */
    @Test void unicodeAndWhitespaceRespectBudgetForEveryCatalogueSize() {
        String siteUrl = "https://" + "a".repeat(56) + ".org";
        GameDigest digest = new GameDigest(mock(JdbcTemplate.class), siteUrl);
        List<GameEntry> games = new ArrayList<>();
        for (int count = 1; count <= 8; count++) {
            UUID gameId = UUID.randomUUID();
            games.add(new GameEntry(gameId, "🎲 Тайна ".repeat(20), "🎲 Система ".repeat(20), 14, 15,
                    siteUrl + "/games/" + gameId, "🎲 Жанр ".repeat(30), "Найдите мага. " + "Долгое приключение ".repeat(20) + "начинается…"));
            List<DigestMessage> messages = digest.messages(games, Platform.DISCORD);
            assertThat(messages).hasSize(1);
            assertThat(messages.getFirst().gameCount()).isEqualTo(count);
            String content = messages.getFirst().payload().get("content").toString();
            assertThat(content.length()).isLessThanOrEqualTo(2000);
            for (GameEntry game : games) assertThat(content).containsOnlyOnce(game.url());
        }
    }

    /** Адрес по умолчанию берётся из конфигурации приложения, включая поддомен нового сайта. */
    @Test void defaultSiteUrlUsesNewWebsite() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(GameDigest.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    GameEntry game = new GameEntry(UUID.randomUUID(), "Игра", "D&D", 1, 4, "https://new.ttg.club/games/example", "", "");
                    var message = context.getBean(GameDigest.class).messages(List.of(game), Platform.DISCORD).getFirst();
                    assertThat(message.payload().get("content").toString()).startsWith("Ищете компанию для приключений? 🎲\n\n")
                            .contains("игры с открытым набором на new.ttg.club.");
                });
    }

    @Test void emptyCatalogueNeverCallsDiscord() {
        PublicationService service = mock(PublicationService.class);
        PublicationSecrets secrets = mock(PublicationSecrets.class);
        GameDigest digest = mock(GameDigest.class);
        DiscordWebhookClient client = mock(DiscordWebhookClient.class);
        Delivery delivery = new Delivery(UUID.randomUUID(), UUID.randomUUID(), 0, "encrypted", Instant.now(), 1, Platform.DISCORD);
        when(digest.preview()).thenReturn(List.of());
        when(service.current(delivery)).thenReturn(true);
        new PublicationScheduler(service, digest, new PublicationSender(secrets, client, mock(TelegramBotClient.class))).publish(delivery);
        verifyNoInteractions(client, secrets);
        verify(service).finish(eq(delivery), eq(new Outcome("SKIPPED", "Нет игр с открытым набором", null, null)), eq(0), any());
    }
}
