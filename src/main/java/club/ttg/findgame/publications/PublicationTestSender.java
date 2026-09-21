package club.ttg.findgame.publications;

import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static club.ttg.findgame.publications.PublicationModels.*;

/** Проверяет сохранённый канал без публикации подборки и без транзакции на время HTTP. */
@Service
public class PublicationTestSender {
    private static final Map<String, Object> DISCORD_MESSAGE = Map.of(
            "content", "✅ Тестовое сообщение TTG Club. Вебхук работает: сообщения из админки доходят в этот канал.",
            "allowed_mentions", Map.of("parse", List.of()));
    private static final Map<String, Object> TELEGRAM_MESSAGE = Map.of(
            "text", "✅ Тестовое сообщение TTG Club. Бот работает: сообщения из админки доходят в этот канал.",
            "link_preview_options", Map.of("is_disabled", true));
    private static final Map<String, Object> VK_MESSAGE = Map.of(
            "message", "✅ Тестовое сообщение TTG Club. Ключ сообщества работает: записи из админки публикуются на этой стене.");
    private final PublicationService service;
    private final PublicationSender sender;

    /** Использует те же шифрование, HTTP-клиент и журнал, что и плановые публикации. */
    public PublicationTestSender(PublicationService service, PublicationSender sender) {
        this.service = service; this.sender = sender;
    }

    /** Фиксирует намерение перед HTTP и возвращает только безопасный результат. */
    public TestResult send(UUID channelId, long revision) {
        Delivery delivery = service.claimTest(channelId, revision, Instant.now());
        Outcome outcome;
        try {
            outcome = service.currentTest(delivery)
                    ? sender.send(delivery, switch (delivery.platform()) {
                        case DISCORD -> DISCORD_MESSAGE;
                        case TELEGRAM -> TELEGRAM_MESSAGE;
                        case VK -> VK_MESSAGE;
                    })
                    : new Outcome("SKIPPED", "Канал изменён или удалён; обновите страницу", null, null);
        } catch (RuntimeException exception) {
            // Исключение может содержать URL: не передаём его в журнал или API.
            outcome = new Outcome("UNKNOWN", "Не удалось подтвердить тестовую отправку; проверьте канал", null, null);
        }
        return service.finishTest(delivery, outcome, Instant.now());
    }
}
