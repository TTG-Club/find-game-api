package club.ttg.findgame.membership.api;

import java.util.List;
import java.util.UUID;

/**
 * Состав игры: мастер и игроки с одобренной заявкой.
 *
 * @param gameId Игра.
 * @param title Название игры.
 * @param masterId Мастер игры.
 * @param players Игроки в порядке подачи заявок.
 */
public record GameMembersResponse(
        UUID gameId,
        String title,
        UUID masterId,
        List<Player> players
) {

    /**
     * Игрок игры.
     *
     * @param userId Пользователь.
     * @param characterName Имя персонажа из заявки; может быть пустым.
     */
    public record Player(UUID userId, String characterName) {
    }
}
