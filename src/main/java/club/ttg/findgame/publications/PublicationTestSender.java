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
    /** Подпись к фото Telegram: у sendPhoto нет превью ссылок. */
    private static final Map<String, Object> TELEGRAM_PHOTO = Map.of("caption", TELEGRAM_MESSAGE.get("text"));
    private final PublicationService service;
    private final PublicationSender sender;
    private final PublicationImages images;

    /** Использует те же шифрование, HTTP-клиент и журнал, что и плановые публикации. */
    public PublicationTestSender(PublicationService service, PublicationSender sender, PublicationImages images) {
        this.service = service; this.sender = sender; this.images = images;
    }

    /** Фиксирует намерение перед HTTP и возвращает только безопасный результат; картинка канала уходит вместе с тестом. */
    public TestResult send(UUID channelId, long revision) {
        Delivery delivery = service.claimTest(channelId, revision, Instant.now());
        Outcome outcome;
        try {
            // Тест проверяет и картинку: отказ от неё не скрывается отправкой без картинки, как в плановом выпуске.
            ImageFile image = delivery.imageUrl() == null ? null : images.load(delivery.imageUrl());
            outcome = service.currentTest(delivery)
                    ? sender.send(delivery, switch (delivery.platform()) {
                        case DISCORD -> DISCORD_MESSAGE;
                        case TELEGRAM -> image == null ? TELEGRAM_MESSAGE : TELEGRAM_PHOTO;
                        case VK -> VK_MESSAGE;
                    }, image)
                    : new Outcome("SKIPPED", "Канал изменён или удалён; обновите страницу", null, null);
        } catch (PublicationImages.Unavailable exception) {
            outcome = new Outcome("FAILED", "Картинка канала недоступна: " + exception.getMessage() + "; выберите её заново", null, null);
        } catch (RuntimeException exception) {
            // Исключение может содержать URL: не передаём его в журнал или API.
            outcome = new Outcome("UNKNOWN", "Не удалось подтвердить тестовую отправку; проверьте канал", null, null);
        }
        return service.finishTest(delivery, outcome, Instant.now());
    }
}
