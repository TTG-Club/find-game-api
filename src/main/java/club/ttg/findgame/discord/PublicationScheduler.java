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
        else outcome = client.send(secrets.decrypt(delivery.secret()), digest.payload(games));
        service.finish(delivery, outcome, games.size(), Instant.now());
    }
}
