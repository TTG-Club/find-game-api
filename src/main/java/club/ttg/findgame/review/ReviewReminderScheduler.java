package club.ttg.findgame.review;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Периодический обход игр с напоминаниями об оценке.
 *
 * Игра обрабатывается в своей транзакции: сбой на одной не откатывает
 * напоминания по остальным.
 */
@Component
public class ReviewReminderScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(ReviewReminderScheduler.class);

    private final ReviewReminderService service;

    public ReviewReminderScheduler(ReviewReminderService service) {
        this.service = service;
    }

    @Scheduled(
            fixedDelayString = "${reviews.reminder-delay:21600000}",
            initialDelayString = "${reviews.reminder-initial-delay:600000}")
    public void tick() {
        Instant now = Instant.now();
        int sent = 0;

        for (UUID gameId : service.findReviewableGameIds(now)) {
            try {
                sent += service.remind(gameId, now);
            } catch (RuntimeException exception) {
                LOG.error("Не удалось напомнить об оценке игры {}", gameId, exception);
            }
        }

        if (sent > 0) {
            LOG.info("Отправлено напоминаний об оценке: {}", sent);
        }
    }
}
