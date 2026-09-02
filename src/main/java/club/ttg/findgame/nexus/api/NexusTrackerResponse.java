package club.ttg.findgame.nexus.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Трекер инициативы в комнате.
 *
 * @param trackerId Идентификатор трекера в core-api.
 * @param canRemove Может ли смотрящий убрать трекер из комнаты.
 */
public record NexusTrackerResponse(
        UUID id,
        UUID trackerId,
        String title,
        UUID createdBy,
        boolean canRemove,
        Instant createdAt
) {
}
