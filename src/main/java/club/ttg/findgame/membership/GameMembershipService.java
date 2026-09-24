package club.ttg.findgame.membership;

import club.ttg.findgame.game.Game;
import club.ttg.findgame.game.GameNotFoundException;
import club.ttg.findgame.game.GameRepository;
import club.ttg.findgame.membership.api.GameMembersResponse;
import club.ttg.findgame.membership.api.GameMembershipResponse;
import club.ttg.findgame.registration.GameRegistration;
import club.ttg.findgame.registration.GameRegistrationRepository;
import club.ttg.findgame.registration.RegistrationStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Кто есть кто в игре — для соседних сервисов.
 *
 * Игроком считается только тот, чью заявку мастер одобрил: неразобранная
 * заявка открывает переписку с мастером, но не доступ к тому, что строится
 * поверх игры. Удалённая игра для соседей не существует.
 */
@Service
public class GameMembershipService {

    private final GameRepository gameRepository;
    private final GameRegistrationRepository registrationRepository;

    public GameMembershipService(
            GameRepository gameRepository,
            GameRegistrationRepository registrationRepository
    ) {
        this.gameRepository = gameRepository;
        this.registrationRepository = registrationRepository;
    }

    /**
     * Роль пользователя в игре.
     *
     * @param gameId Игра.
     * @param userId Пользователь.
     * @return Роль вместе с мастером и названием игры.
     */
    @Transactional(readOnly = true)
    public GameMembershipResponse membership(UUID gameId, UUID userId) {
        Game game = findGame(gameId);

        return new GameMembershipResponse(
                game.getId(), game.getTitle(), game.getMasterId(), userId, roleOf(game, userId));
    }

    /**
     * Состав игры: мастер и игроки с одобренной заявкой.
     *
     * @param gameId Игра.
     * @return Мастер и игроки в порядке подачи заявок.
     */
    @Transactional(readOnly = true)
    public GameMembersResponse members(UUID gameId) {
        Game game = findGame(gameId);

        List<GameMembersResponse.Player> players = registrationRepository
                .findAllByGameIdAndStatus(gameId, RegistrationStatus.APPROVED).stream()
                .sorted(Comparator.comparing(GameRegistration::getCreatedAt))
                .map(registration -> new GameMembersResponse.Player(
                        registration.getPlayerId(), registration.getCharacterName()))
                .toList();

        return new GameMembersResponse(game.getId(), game.getTitle(), game.getMasterId(), players);
    }

    private GameRole roleOf(Game game, UUID userId) {
        if (game.getMasterId().equals(userId)) {
            return GameRole.MASTER;
        }

        boolean approved = registrationRepository.existsByGameIdAndPlayerIdAndStatus(
                game.getId(), userId, RegistrationStatus.APPROVED);

        return approved ? GameRole.PLAYER : GameRole.NONE;
    }

    private Game findGame(UUID gameId) {
        return gameRepository.findByIdAndDeletedAtIsNull(gameId)
                .orElseThrow(() -> new GameNotFoundException(gameId));
    }
}
