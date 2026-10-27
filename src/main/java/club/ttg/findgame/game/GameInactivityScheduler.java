package club.ttg.findgame.game;

import club.ttg.findgame.account.AuthMailClient;
import club.ttg.findgame.config.GameInactivityProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Instant;
import java.util.UUID;

/**
 * Проход по брошенным играм: сначала отменяет те, по которым срок ответа
 * вышел, затем задаёт вопрос новым. Письмо уходит уже после транзакции —
 * сетевой вызов не держит блокировку игры.
 */
@Slf4j
@Configuration
@EnableScheduling
@EnableConfigurationProperties(GameInactivityProperties.class)
public class GameInactivityScheduler {

    private final GameInactivityService service;
    private final AuthMailClient mailClient;
    private final GameInactivityProperties properties;
    private final String siteUrl;

    public GameInactivityScheduler(
            GameInactivityService service,
            AuthMailClient mailClient,
            GameInactivityProperties properties,
            @Value("${discord-publications.site-url}") String siteUrl
    ) {
        this.service = service;
        this.mailClient = mailClient;
        this.properties = properties;
        this.siteUrl = siteUrl.endsWith("/") ? siteUrl.substring(0, siteUrl.length() - 1) : siteUrl;
    }

    @Scheduled(
            fixedDelayString = "${game-inactivity.poll-delay:15m}",
            initialDelayString = "${game-inactivity.poll-delay:15m}")
    public void tick() {
        if (!properties.isEnabled()) {
            return;
        }

        Instant now = Instant.now();

        for (UUID gameId : service.findDueForAutoCancel(now)) {
            try {
                if (service.cancel(gameId, now)) {
                    log.info("Игра {} отменена: мастер не подтвердил, что она актуальна", gameId);
                }
            } catch (RuntimeException exception) {
                log.error("Не удалось отменить брошенную игру {}", gameId, exception);
            }
        }

        for (UUID gameId : service.findInactive(now)) {
            try {
                service.warn(gameId, now).ifPresent(warning -> mailClient.sendGameInactive(
                        warning.masterId(), warning.gameTitle(),
                        siteUrl + "/games/" + warning.gameId(), warning.cancelAt()));
            } catch (RuntimeException exception) {
                log.error("Не удалось спросить мастера об актуальности игры {}", gameId, exception);
            }
        }
    }
}
