package club.ttg.findgame.nexus.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Лист персонажа в комнате.
 *
 * @param ownerId Кто выложил лист.
 * @param shareToken Токен общего доступа: по нему лист открывается.
 * @param canRemove Может ли смотрящий убрать лист из комнаты.
 */
public record NexusSheetResponse(
        UUID id,
        UUID ownerId,
        String shareToken,
        String characterName,
        boolean canRemove,
        Instant createdAt
) {
}
