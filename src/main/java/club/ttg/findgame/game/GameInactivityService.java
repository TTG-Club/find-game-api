package club.ttg.findgame.game;

import club.ttg.findgame.config.GameInactivityProperties;
import club.ttg.findgame.notification.NotificationService;
import club.ttg.findgame.notification.NotificationType;
import club.ttg.findgame.registration.GameRegistration;
import club.ttg.findgame.registration.GameRegistrationRepository;
import club.ttg.findgame.registration.RegistrationStatus;
import club.ttg.findgame.session.GameSessionRepository;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Брошенные игры. Мастер, который долго ничего не делает в открытой игре,
 * получает вопрос «Игра ещё актуальна?»; не ответил в срок — игра отменяется.
 *
 * Отменяется, а не завершается: игра не состоялась, и среди сыгранных
 * игр мастера ей не место.
 *
 * Каждая игра разбирается в своей транзакции под блокировкой строки: условия
 * перепроверяются уже под ней, поэтому параллельный проход другой реплики или
 * ответ мастера в ту же минуту не приводят ни к двойному вопросу, ни к отмене
 * живой игры.
 */
@Service
public class GameInactivityService {

    private final GameRepository repository;
    private final GameSessionRepository sessionRepository;
    private final GameRegistrationRepository registrationRepository;
    private final NotificationService notificationService;
    private final GameInactivityProperties properties;

    public GameInactivityService(
            GameRepository repository,
            GameSessionRepository sessionRepository,
            GameRegistrationRepository registrationRepository,
            NotificationService notificationService,
            GameInactivityProperties properties
    ) {
        this.repository = repository;
        this.sessionRepository = sessionRepository;
        this.registrationRepository = registrationRepository;
        this.notificationService = notificationService;
        this.properties = properties;
    }

    /** Игры, мастеру которых пора задать вопрос. */
    @Transactional(readOnly = true)
    public List<UUID> findInactive(Instant now) {
        return repository.findInactiveIds(
                now.minus(properties.getIdlePeriod()), now, Limit.of(properties.getBatchSize()));
    }

    /** Игры, по которым срок ответа вышел. */
    @Transactional(readOnly = true)
    public List<UUID> findDueForAutoCancel(Instant now) {
        return repository.findDueForAutoCancelIds(now, Limit.of(properties.getBatchSize()));
    }

    /**
     * Спрашивает мастера, актуальна ли игра, и назначает срок автоотмены.
     *
     * @param gameId Игра.
     * @param now Момент прохода.
     * @return Вопрос, если он задан: по нему уходит письмо. Пусто, если игра
     * за это время ожила или уже под вопросом.
     */
    @Transactional
    public Optional<InactivityWarning> warn(UUID gameId, Instant now) {
        Game game = repository.findByIdForUpdate(gameId).orElse(null);

        if (game == null
                || game.getStatus() != GameStatus.OPEN
                || game.getDeletedAt() != null
                || game.getAutoCancelAt() != null
                || !game.getLastActivityAt().isBefore(now.minus(properties.getIdlePeriod()))
                || sessionRepository.existsOngoingOrUpcoming(gameId, now)) {
            return Optional.empty();
        }

        Instant cancelAt = now.plus(properties.getGracePeriod());

        game.setAutoCancelAt(cancelAt);
        repository.save(game);

        notificationService.notifyUser(
                game.getMasterId(), null, NotificationType.GAME_INACTIVITY_WARNING,
                game.getId(), game.getTitle(), null, null, cancelAt.toString());

        return Optional.of(new InactivityWarning(game.getMasterId(), game.getId(), game.getTitle(), cancelAt));
    }

    /**
     * Отменяет игру, мастер которой так и не ответил.
     *
     * @param gameId Игра.
     * @param now Момент прохода.
     * @return Отменена ли игра: мастер мог ответить в последний момент.
     */
    @Transactional
    public boolean cancel(UUID gameId, Instant now) {
        Game game = repository.findByIdForUpdate(gameId).orElse(null);

        if (game == null
                || game.getStatus() != GameStatus.OPEN
                || game.getDeletedAt() != null
                || game.getAutoCancelAt() == null
                || game.getAutoCancelAt().isAfter(now)) {
            return false;
        }

        game.setStatus(GameStatus.CANCELLED);
        game.setAutoCancelAt(null);
        repository.save(game);

        // Для состава это обычная отмена игры; мастеру — объяснение, почему
        // она отменилась без него.
        List<UUID> players = registrationRepository
                .findAllByGameIdAndStatus(gameId, RegistrationStatus.APPROVED).stream()
                .map(GameRegistration::getPlayerId)
                .toList();

        notificationService.notifyUsers(
                players, game.getMasterId(), NotificationType.GAME_CANCELLED,
                game.getId(), game.getTitle(), null, null);
        notificationService.notifyUser(
                game.getMasterId(), null, NotificationType.GAME_CANCELLED_FOR_INACTIVITY,
                game.getId(), game.getTitle(), null, null);

        return true;
    }

    /** Заданный мастеру вопрос: из него собирается письмо. */
    public record InactivityWarning(UUID masterId, UUID gameId, String gameTitle, Instant cancelAt) {
    }
}
