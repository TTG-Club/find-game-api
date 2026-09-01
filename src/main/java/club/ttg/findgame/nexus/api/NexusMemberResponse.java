package club.ttg.findgame.nexus.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Участник комнаты. Имена живут в core-api: здесь только идентификаторы.
 *
 * @param owner Владелец комнаты — у комнаты игры это её мастер.
 */
public record NexusMemberResponse(
        UUID userId,
        boolean owner,
        Instant joinedAt
) {
}
