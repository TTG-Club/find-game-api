package club.ttg.findgame.review;

import club.ttg.findgame.game.Game;
import club.ttg.findgame.game.GameRepository;
import club.ttg.findgame.notification.NotificationService;
import club.ttg.findgame.notification.NotificationType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Напоминает оценить тех, с кем играл.
 *
 * Без напоминания до оценки доходят единицы: после игры никто сам не ищет,
 * где её поставить. Напоминание одно на человека в игре — второе уже
 * воспринимается как спам.
 */
@Service
public class ReviewReminderService {

    private final GameRepository gameRepository;
    private final GameReviewService reviewService;
    private final ReviewReminderStore reminderStore;
    private final NotificationService notificationService;

    public ReviewReminderService(
            GameRepository gameRepository,
            GameReviewService reviewService,
            ReviewReminderStore reminderStore,
            NotificationService notificationService
    ) {
        this.gameRepository = gameRepository;
        this.reviewService = reviewService;
        this.reminderStore = reminderStore;
        this.notificationService = notificationService;
    }

    /** Игры, по которым ещё уместно напоминать. */
    @Transactional(readOnly = true)
    public List<UUID> findReviewableGameIds(Instant now) {
        return gameRepository.findReviewable(now.minus(GameReviewService.PENDING_AFTER_FINISH))
                .stream()
                .map(Game::getId)
                .toList();
    }

    /**
     * Напоминает всем в игре, кто ещё не оценил сыгравших с ним.
     *
     * @param gameId Игра.
     * @param now Момент проверки.
     * @return Сколько напоминаний отправлено.
     */
    @Transactional
    public int remind(UUID gameId, Instant now) {
        Game game = gameRepository.findByIdAndDeletedAtIsNull(gameId).orElse(null);

        if (game == null) {
            return 0;
        }

        int sent = 0;

        for (UUID authorId : reviewService.findMissing(game, now).keySet()) {
            if (!reminderStore.claim(game.getId(), authorId, now)) {
                continue;
            }

            notificationService.notifyUser(
                    authorId, null, NotificationType.REVIEW_REQUESTED,
                    game.getId(), game.getTitle(), null, null);
            sent++;
        }

        return sent;
    }
}
