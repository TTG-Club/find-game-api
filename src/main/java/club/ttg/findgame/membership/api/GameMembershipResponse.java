package club.ttg.findgame.membership.api;

import club.ttg.findgame.membership.GameRole;

import java.util.UUID;

/**
 * Роль одного пользователя в игре.
 *
 * @param gameId Игра.
 * @param title Название игры — чтобы сосед показал его, не спрашивая второй раз.
 * @param masterId Мастер игры.
 * @param userId О ком спрашивали.
 * @param role Кем он приходится игре.
 */
public record GameMembershipResponse(
        UUID gameId,
        String title,
        UUID masterId,
        UUID userId,
        GameRole role
) {
}
