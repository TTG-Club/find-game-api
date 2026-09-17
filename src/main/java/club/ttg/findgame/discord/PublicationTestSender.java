package club.ttg.findgame.discord;

import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static club.ttg.findgame.discord.PublicationModels.*;

/** Проверяет сохранённый вебхук без публикации подборки и без транзакции на время HTTP. */
@Service
public class PublicationTestSender {
    private static final Map<String, Object> MESSAGE = Map.of(
            "content", "✅ Тестовое сообщение TTG Club. Вебхук работает: сообщения из админки доходят в этот канал.",
            "allowed_mentions", Map.of("parse", List.of()));
    private final PublicationService service;
    private final WebhookSecrets secrets;
    private final DiscordWebhookClient client;

    /** Использует те же шифрование, HTTP-клиент и журнал, что и плановые публикации. */
    public PublicationTestSender(PublicationService service, WebhookSecrets secrets, DiscordWebhookClient client) {
        this.service = service; this.secrets = secrets; this.client = client;
    }

    /** Фиксирует намерение перед HTTP и возвращает только безопасный результат. */
    public TestResult send(UUID channelId, long revision) {
        Delivery delivery = service.claimTest(channelId, revision, Instant.now());
        Outcome outcome;
        try {
            outcome = service.currentTest(delivery)
                    ? client.send(secrets.decrypt(delivery.secret()), MESSAGE)
                    : new Outcome("SKIPPED", "Канал изменён или удалён; обновите страницу", null, null);
        } catch (RuntimeException exception) {
            // Исключение может содержать URL: не передаём его в журнал или API.
            outcome = new Outcome("UNKNOWN", "Не удалось подтвердить тестовую отправку; проверьте канал", null, null);
        }
        return service.finishTest(delivery, outcome, Instant.now());
    }
}
