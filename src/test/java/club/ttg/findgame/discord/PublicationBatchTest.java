package club.ttg.findgame.discord;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static club.ttg.findgame.discord.PublicationModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Проверяет частичную доставку обычных сообщений и отсутствие повторных публикаций. */
class PublicationBatchTest {
    private final PublicationService service = mock(PublicationService.class);
    private final WebhookSecrets secrets = mock(WebhookSecrets.class);
    private final GameDigest digest = mock(GameDigest.class);
    private final DiscordWebhookClient client = mock(DiscordWebhookClient.class);
    private final PublicationScheduler scheduler = new PublicationScheduler(service, secrets, digest, client);
    private final Delivery delivery = new Delivery(UUID.randomUUID(), UUID.randomUUID(), 0, "encrypted", Instant.now(), 1);
    private final List<DigestMessage> messages = List.of(
            new DigestMessage(Map.of("content", "Первая часть"), 2),
            new DigestMessage(Map.of("content", "Вторая часть"), 1),
            new DigestMessage(Map.of("content", "Третья часть"), 1));

    /** Подготавливает четыре игры в трёх сообщениях без реальных HTTP-запросов. */
    PublicationBatchTest() {
        List<GameEntry> games = java.util.stream.IntStream.range(0, 4).mapToObj(index ->
                new GameEntry(UUID.randomUUID(), "Игра " + index, "D&D", 1, 4,
                        "https://new.ttg.club/games/example", "", "Описание.")).toList();
        when(service.current(delivery)).thenReturn(true);
        when(secrets.decrypt("encrypted")).thenReturn("validated-webhook");
        when(digest.preview()).thenReturn(games);
        when(digest.messages(games)).thenReturn(messages);
    }

    /** Не оставляет прерывание тестового потока для соседних тестов. */
    @AfterEach void clearInterruption() { Thread.interrupted(); }

    /** Успех фиксируется только после всех частей; сохраняется ссылка на первую. */
    @Test void sendsAllPartsInOrder() {
        when(client.send(anyString(), anyMap())).thenReturn(sent("111111111111111111"), sent("222222222222222222"), sent("333333333333333333"));
        scheduler.publish(delivery);
        var order = inOrder(client);
        for (DigestMessage message : messages) order.verify(client).send("validated-webhook", message.payload());
        Outcome outcome = result();
        assertThat(outcome.status()).isEqualTo("SENT");
        assertThat(outcome.messageId()).isEqualTo("111111111111111111");
        assertThat(outcome.detail()).contains("3");
    }

    /** 429 до первой части сохраняет прежний безопасный повтор всего выпуска. */
    @Test void firstPartRateLimitCanRetry() {
        Outcome retry = new Outcome("RETRY", "Лимит", null, Instant.now().plusSeconds(30));
        when(client.send(anyString(), anyMap())).thenReturn(retry);
        scheduler.publish(delivery);
        assertThat(result()).isEqualTo(retry);
        verify(client, times(1)).send(anyString(), anyMap());
    }

    /** Частичный выпуск не повторяется целиком, но ограничение Discord передаётся общей паузе. */
    @Test void rateLimitAfterSuccessNeverRetriesPublishedGames() {
        Instant retryAt = Instant.now().plusSeconds(30);
        when(client.send(anyString(), anyMap())).thenReturn(sent("111111111111111111"), new Outcome("RETRY", "Лимит", null, retryAt));
        scheduler.publish(delivery);
        Outcome outcome = result();
        assertThat(outcome.status()).isEqualTo("FAILED");
        assertThat(outcome.retryAt()).isEqualTo(retryAt);
        assertThat(outcome.detail()).contains("1/3", "2", "Автоповтора нет");
        verify(client, times(2)).send(anyString(), anyMap());
    }

    /** Смена настроек между частями останавливает следующую отправку. */
    @Test void changedSettingsStopRemainingMessages() {
        when(service.current(delivery)).thenReturn(true, false);
        when(client.send(anyString(), anyMap())).thenReturn(sent("111111111111111111"));
        scheduler.publish(delivery);
        assertThat(result().detail()).contains("1/3", "Настройки изменены");
        verify(client, times(1)).send(anyString(), anyMap());
    }

    /** Прерывание сохраняется, а уже подтверждённые части учитываются в безопасной ошибке. */
    @Test void interruptedBatchDoesNotSendNextPart() {
        when(client.send(anyString(), anyMap())).thenAnswer(invocation -> {
            Thread.currentThread().interrupt();
            return sent("111111111111111111");
        });
        scheduler.publish(delivery);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        assertThat(result().status()).isEqualTo("UNKNOWN");
        verify(client, times(1)).send(anyString(), anyMap());
    }

    /** Неоднозначный исход второй части не маскируется успехом и не раскрывает вебхук. */
    @Test void networkFailureKeepsPartialOutcomeSafe() {
        when(client.send(anyString(), anyMap())).thenReturn(sent("111111111111111111"))
                .thenThrow(new IllegalStateException("validated-webhook"));
        scheduler.publish(delivery);
        Outcome outcome = result();
        assertThat(outcome.status()).isEqualTo("UNKNOWN");
        assertThat(outcome.detail()).contains("1/3").doesNotContain("validated-webhook");
        verify(client, times(2)).send(anyString(), anyMap());
    }

    /** Извлекает итог выпуска и проверяет прежний смысл счётчика игр в подборке. */
    private Outcome result() {
        ArgumentCaptor<Outcome> outcome = ArgumentCaptor.forClass(Outcome.class);
        verify(service).finish(eq(delivery), outcome.capture(), eq(4), any());
        return outcome.getValue();
    }

    /** Создаёт подтверждение одной части. */
    private static Outcome sent(String messageId) { return new Outcome("SENT", "Опубликовано", messageId, null); }
}
