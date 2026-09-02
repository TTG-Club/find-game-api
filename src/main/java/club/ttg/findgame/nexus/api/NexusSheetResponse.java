package club.ttg.findgame.nexus.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

/**
 * Лист персонажа в комнате.
 *
 * @param ownerId Кто выложил лист.
 * @param shareToken Токен общего доступа: по нему лист открывается. Уходит
 *                   только владельцу листа и владельцу комнаты — остальным
 *                   чужой лист не показывают, а токен и есть ключ к нему.
 * @param canRemove Может ли смотрящий убрать лист из комнаты.
 */
public record NexusSheetResponse(
        UUID id,
        UUID ownerId,
        @JsonInclude(JsonInclude.Include.NON_NULL) String shareToken,
        String characterName,
        boolean canRemove,
        Instant createdAt
) {
}
