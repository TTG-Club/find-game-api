package club.ttg.findgame.follow;

import club.ttg.findgame.follow.api.FollowResponse;
import club.ttg.findgame.game.Game;
import club.ttg.findgame.game.GameAccessDeniedException;
import club.ttg.findgame.game.GameNotFoundException;
import club.ttg.findgame.game.GameRepository;
import club.ttg.findgame.game.GameStatus;
import club.ttg.findgame.game.GameVisibility;
import club.ttg.findgame.notification.NotificationService;
import club.ttg.findgame.notification.NotificationType;
import club.ttg.findgame.registration.GameRegistrationRepository;
import club.ttg.findgame.registration.RegistrationStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Отметки участников друг о друге.
 *
 * Игрок отмечает мастера, чтобы не пропустить его новую игру; мастер отмечает
 * игрока, чтобы позвать его в следующую. Отметка односторонняя и согласия
 * второй стороны не требует — это закладка в своём списке, а не дружба.
 *
 * Мастера отмечают кем угодно: наружу это ничего не рассылает. Игрока
 * отмечает только тот мастер, у кого он уже играл или просился играть: иначе
 * приглашение стало бы способом писать незнакомым людям по одному лишь
 * идентификатору.
 */
@Service
public class FollowService {

    private final UserFollowRepository repository;
    private final GameRepository gameRepository;
    private final GameRegistrationRepository registrationRepository;
    private final NotificationService notificationService;

    public FollowService(
            UserFollowRepository repository,
            GameRepository gameRepository,
            GameRegistrationRepository registrationRepository,
            NotificationService notificationService
    ) {
        this.repository = repository;
        this.gameRepository = gameRepository;
        this.registrationRepository = registrationRepository;
        this.notificationService = notificationService;
    }

    /**
     * Отмечает мастера: его новые игры будут приходить уведомлением.
     *
     * @param playerId Кто отмечает.
     * @param masterId Кого отмечают.
     */
    @Transactional
    public void followMaster(UUID playerId, UUID masterId) {
        add(playerId, masterId, FollowKind.MASTER_FOLLOW);
    }

    /**
     * Снимает отметку с мастера.
     *
     * @param playerId Кто отмечал.
     * @param masterId Кого отмечали.
     */
    @Transactional
    public void unfollowMaster(UUID playerId, UUID masterId) {
        repository.deleteByOwnerIdAndTargetIdAndKind(
                playerId, masterId, FollowKind.MASTER_FOLLOW);
    }

    /**
     * Отмечает игрока, чтобы звать его в свои игры. Отметить можно того, кто
     * уже просился в игру этого мастера.
     *
     * @param masterId Кто отмечает.
     * @param playerId Кого отмечают.
     */
    @Transactional
    public void bookmarkPlayer(UUID masterId, UUID playerId) {
        boolean played = registrationRepository.existsAtMasterGames(
                masterId, playerId, RegistrationStatus.REJECTED);

        if (!played) {
            throw new FollowNotAllowedException("Этот игрок в ваших играх не участвовал");
        }

        add(masterId, playerId, FollowKind.PLAYER_BOOKMARK);
    }

    /**
     * Снимает отметку с игрока.
     *
     * @param masterId Кто отмечал.
     * @param playerId Кого отмечали.
     */
    @Transactional
    public void unbookmarkPlayer(UUID masterId, UUID playerId) {
        repository.deleteByOwnerIdAndTargetIdAndKind(
                masterId, playerId, FollowKind.PLAYER_BOOKMARK);
    }

    /**
     * Отмеченные мастера — свежие сверху.
     * @param playerId Владелец списка.
     */
    @Transactional(readOnly = true)
    public List<FollowResponse> findFollowedMasters(UUID playerId) {
        return find(playerId, FollowKind.MASTER_FOLLOW);
    }

    /**
     * Отмеченные игроки — свежие сверху.
     * @param masterId Владелец списка.
     */
    @Transactional(readOnly = true)
    public List<FollowResponse> findBookmarkedPlayers(UUID masterId) {
        return find(masterId, FollowKind.PLAYER_BOOKMARK);
    }

    /**
     * Зовёт отмеченного игрока в свою игру.
     *
     * Приглашение — это уведомление со ссылкой, а не место в составе: игрок
     * переходит и подаёт заявку сам. Взять человека в игру, не спросив его,
     * значило бы записать его в чужое расписание.
     *
     * @param masterId Кто зовёт.
     * @param gameId Куда зовут.
     * @param playerId Кого зовут.
     */
    @Transactional
    public void invitePlayer(UUID masterId, UUID gameId, UUID playerId) {
        Game game = gameRepository.findByIdAndDeletedAtIsNull(gameId)
                .orElseThrow(() -> new GameNotFoundException(gameId));

        if (!game.getMasterId().equals(masterId)) {
            throw new GameAccessDeniedException();
        }

        boolean bookmarked = repository.existsByOwnerIdAndTargetIdAndKind(
                masterId, playerId, FollowKind.PLAYER_BOOKMARK);

        if (!bookmarked) {
            throw new FollowNotAllowedException("Звать можно отмеченного игрока");
        }

        if (game.getStatus() != GameStatus.OPEN || game.isRecruitmentClosed()) {
            throw new FollowNotAllowedException("Набор в эту игру закрыт");
        }

        boolean applied = registrationRepository.existsByGameIdAndPlayerIdAndStatusNot(
                gameId, playerId, RegistrationStatus.REJECTED);

        if (applied) {
            throw new FollowNotAllowedException("Этот игрок уже подал заявку");
        }

        boolean invited = notificationService.wasNotified(
                playerId, NotificationType.GAME_INVITE, gameId);

        if (invited) {
            throw new FollowNotAllowedException("Этого игрока уже звали в эту игру");
        }

        notificationService.notifyUser(
                playerId,
                masterId,
                NotificationType.GAME_INVITE,
                gameId,
                game.getTitle(),
                null,
                null);
    }

    /**
     * Рассылает новую игру тем, кто отметил её мастера.
     *
     * Приватная игра не рассылается: её показывают по ссылке, и подписка не
     * повод её раскрывать.
     *
     * @param game Только что созданная игра.
     */
    @Transactional
    public void announceGame(Game game) {
        if (game.getVisibility() != GameVisibility.PUBLIC) {
            return;
        }

        List<UUID> followers = repository
                .findAllByTargetIdAndKind(game.getMasterId(), FollowKind.MASTER_FOLLOW)
                .stream()
                .map(UserFollow::getOwnerId)
                .toList();

        if (followers.isEmpty()) {
            return;
        }

        notificationService.notifyUsers(
                followers,
                game.getMasterId(),
                NotificationType.MASTER_PUBLISHED_GAME,
                game.getId(),
                game.getTitle(),
                null,
                null);
    }

    /** Ставит отметку; повторная ничего не меняет. */
    private void add(UUID ownerId, UUID targetId, FollowKind kind) {
        if (ownerId.equals(targetId)) {
            throw new FollowNotAllowedException("Себя не отмечают");
        }

        if (repository.existsByOwnerIdAndTargetIdAndKind(ownerId, targetId, kind)) {
            return;
        }

        repository.save(UserFollow.of(ownerId, targetId, kind));
    }

    private List<FollowResponse> find(UUID ownerId, FollowKind kind) {
        return repository.findAllByOwnerIdAndKindOrderByCreatedAtDesc(ownerId, kind).stream()
                .map(follow -> new FollowResponse(follow.getTargetId(), follow.getCreatedAt()))
                .toList();
    }
}
