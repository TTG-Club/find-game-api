package club.ttg.findgame.discord;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import java.time.Instant;
import java.util.List;
import static club.ttg.findgame.discord.PublicationModels.*;

/** Планировщик с постоянным журналом, общим для всех реплик сервиса. */
@Configuration
@EnableScheduling
public class PublicationScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(PublicationScheduler.class);
    private final PublicationService service;
    private final WebhookSecrets secrets;
    private final GameDigest digest;
    private final DiscordWebhookClient client;

    /** Собирает отправку из независимо проверяемых частей. */
    public PublicationScheduler(PublicationService service, WebhookSecrets secrets, GameDigest digest, DiscordWebhookClient client) {
        this.service = service; this.secrets = secrets; this.digest = digest; this.client = client;
    }

    /** Обрабатывает ограниченную порцию, не удерживая транзакцию во время HTTP. */
    @Scheduled(fixedDelayString = "${discord-publications.poll-delay:15000}", initialDelayString = "${discord-publications.poll-delay:15000}")
    public void tick() {
        if (!secrets.configured()) return;
        for (int index = 0; index < 20 && !Thread.currentThread().isInterrupted(); index++) {
            Delivery delivery = service.claim(Instant.now());
            if (delivery == null) return;
            try { publish(delivery); }
            catch (RuntimeException exception) {
                // URL вебхука может присутствовать в сетевом исключении: не логируем объект исключения.
                LOG.error("Не удалось завершить Discord-публикацию {}", delivery.runId());
                service.finish(delivery, new Outcome("UNKNOWN", "Отправка прервана; проверьте канал", null, null), 0, Instant.now());
            }
        }
    }

    /** Отправляет только актуальную подборку; пустой каталог не создаёт сообщение. */
    void publish(Delivery delivery) {
        List<GameEntry> games = digest.preview();
        Outcome outcome;
        if (!service.current(delivery)) outcome = new Outcome("SKIPPED", "Настройки изменены", null, null);
        else if (games.isEmpty()) outcome = new Outcome("SKIPPED", "Нет игр с открытым набором", null, null);
        else outcome = sendMessages(delivery, digest.messages(games));
        service.finish(delivery, outcome, games.size(), Instant.now());
    }

    /** Отправляет части последовательно; после частичного успеха запрещает повтор всего выпуска. */
    private Outcome sendMessages(Delivery delivery, List<DigestMessage> messages) {
        String webhookUrl = secrets.decrypt(delivery.secret());
        String firstMessageId = null;
        int sentMessages = 0;
        int sentGames = 0;
        for (DigestMessage message : messages) {
            Outcome outcome = sendNext(delivery, message, webhookUrl, sentMessages > 0);
            if (!outcome.status().equals("SENT")) {
                if (sentMessages == 0) return outcome;
                String status = outcome.status().equals("UNKNOWN") ? "UNKNOWN" : "FAILED";
                return new Outcome(status, "Частично: " + sentMessages + "/" + messages.size()
                        + " сообщений, " + sentGames + " игр. " + outcome.detail() + ". Автоповтора нет.",
                        firstMessageId, outcome.retryAt());
            }
            if (firstMessageId == null) firstMessageId = outcome.messageId();
            sentMessages++;
            sentGames += message.gameCount();
        }
        return new Outcome("SENT", "Опубликовано сообщений: " + sentMessages, firstMessageId, null);
    }

    /** Между частями проверяет отмену и настройки; не раскрывает секрет при неожиданной ошибке. */
    private Outcome sendNext(Delivery delivery, DigestMessage message, String webhookUrl, boolean checkSettings) {
        if (Thread.currentThread().isInterrupted()) return new Outcome("UNKNOWN", "Отправка прервана; проверьте канал", null, null);
        try {
            if (checkSettings && !service.current(delivery)) return new Outcome("SKIPPED", "Настройки изменены", null, null);
            return client.send(webhookUrl, message.payload());
        } catch (RuntimeException exception) {
            return new Outcome("UNKNOWN", "Нет подтверждения доставки; проверьте канал", null, null);
        }
    }
}
