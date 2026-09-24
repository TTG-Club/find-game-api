package club.ttg.findgame.publications;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import java.time.Instant;
import java.util.List;
import static club.ttg.findgame.publications.PublicationModels.*;

/** Планировщик с постоянным журналом, общим для всех реплик сервиса. */
@Configuration
@EnableScheduling
public class PublicationScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(PublicationScheduler.class);
    private final PublicationService service;
    private final GameDigest digest;
    private final PublicationSender sender;
    private final PublicationImages images;

    /** Собирает отправку из независимо проверяемых частей. */
    public PublicationScheduler(PublicationService service, GameDigest digest, PublicationSender sender, PublicationImages images) {
        this.service = service; this.digest = digest; this.sender = sender; this.images = images;
    }

    /** Обрабатывает ограниченную порцию, не удерживая транзакцию во время HTTP. */
    @Scheduled(fixedDelayString = "${discord-publications.poll-delay:15000}", initialDelayString = "${discord-publications.poll-delay:15000}")
    public void tick() {
        for (int index = 0; index < 20 && !Thread.currentThread().isInterrupted(); index++) {
            Delivery delivery = service.claim(Instant.now());
            if (delivery == null) return;
            try { publish(delivery); }
            catch (RuntimeException exception) {
                // Токен бота или URL вебхука может присутствовать в сетевом исключении: не логируем объект исключения.
                LOG.error("Не удалось завершить публикацию {}", delivery.runId());
                service.finish(delivery, new Outcome("UNKNOWN", "Отправка прервана; проверьте канал", null, null), 0, Instant.now());
            }
        }
    }

    /** Отправляет только актуальную подборку; пустой каталог не создаёт сообщение. */
    void publish(Delivery delivery) {
        List<GameEntry> games = digest.preview();
        Sent sent;
        if (!service.current(delivery)) sent = new Sent(new Outcome("SKIPPED", "Настройки изменены", null, null), games.size());
        else if (games.isEmpty()) sent = new Sent(new Outcome("SKIPPED", "Нет игр с открытым набором", null, null), 0);
        else sent = sendDigest(delivery, games);
        service.finish(delivery, sent.outcome(), sent.gameCount(), Instant.now());
    }

    /** Картинка дополняет подборку: без неё или при отказе платформы выпуск уходит как обычно, текстом. */
    private Sent sendDigest(Delivery delivery, List<GameEntry> games) {
        String imageProblem = null;
        if (delivery.imageUrl() != null) {
            try {
                ImageFile image = images.load(delivery.imageUrl());
                Sent sent = sendMessages(delivery, digest.messages(games, delivery.platform(), true), image);
                // Отказ до первой доставленной части: платформа ничего не опубликовала, и отправка без картинки не создаст дубль.
                if (!sent.outcome().status().equals("FAILED") || sent.outcome().messageId() != null) return sent;
                imageProblem = sent.outcome().detail();
            } catch (PublicationImages.Unavailable exception) {
                imageProblem = "Картинка недоступна: " + exception.getMessage();
            }
        }
        Sent sent = sendMessages(delivery, digest.messages(games, delivery.platform()), null);
        Outcome outcome = sent.outcome();
        if (imageProblem == null || !outcome.status().equals("SENT")) return sent;
        return new Sent(new Outcome("SENT", "Без картинки (" + imageProblem + "). " + outcome.detail(), outcome.messageId(), null),
                sent.gameCount());
    }

    /** Отправляет части последовательно; после частичного успеха запрещает повтор всего выпуска. */
    private Sent sendMessages(Delivery delivery, List<DigestMessage> messages, ImageFile image) {
        // Подпись к фото Telegram может вместить не все игры подборки: в журнал идёт число игр выпуска.
        int gameCount = messages.stream().mapToInt(DigestMessage::gameCount).sum();
        String firstMessageId = null;
        int sentMessages = 0;
        int sentGames = 0;
        for (DigestMessage message : messages) {
            Outcome outcome = sendNext(delivery, message, message.withImage() ? image : null, sentMessages > 0);
            if (!outcome.status().equals("SENT")) {
                if (sentMessages == 0) return new Sent(outcome, gameCount);
                String status = outcome.status().equals("UNKNOWN") ? "UNKNOWN" : "FAILED";
                return new Sent(new Outcome(status, "Частично: " + sentMessages + "/" + messages.size()
                        + " сообщений, " + sentGames + " игр. " + outcome.detail() + ". Автоповтора нет.",
                        firstMessageId, outcome.retryAt()), gameCount);
            }
            if (firstMessageId == null) firstMessageId = outcome.messageId();
            sentMessages++;
            sentGames += message.gameCount();
        }
        return new Sent(new Outcome("SENT", "Опубликовано сообщений: " + sentMessages, firstMessageId, null), gameCount);
    }

    /** Между частями проверяет отмену и настройки; не раскрывает секрет при неожиданной ошибке. */
    private Outcome sendNext(Delivery delivery, DigestMessage message, ImageFile image, boolean checkSettings) {
        if (Thread.currentThread().isInterrupted()) return new Outcome("UNKNOWN", "Отправка прервана; проверьте канал", null, null);
        try {
            if (checkSettings && !service.current(delivery)) return new Outcome("SKIPPED", "Настройки изменены", null, null);
            return sender.send(delivery, message.payload(), image);
        } catch (RuntimeException exception) {
            return new Outcome("UNKNOWN", "Нет подтверждения доставки; проверьте канал", null, null);
        }
    }

    /** Итог выпуска и число игр в нём для журнала. */
    private record Sent(Outcome outcome, int gameCount) {}
}
