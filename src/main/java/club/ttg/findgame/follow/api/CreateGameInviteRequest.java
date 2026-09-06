package club.ttg.findgame.follow.api;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Приглашение отмеченного игрока в игру.
 *
 * @param playerId Кого зовут.
 */
public record CreateGameInviteRequest(@NotNull UUID playerId) {
}
