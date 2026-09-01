package club.ttg.findgame.nexus.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

/**
 * Комната глазами того, кто её открыл.
 *
 * @param inviteCode Код приглашения; уходит только владельцу самостоятельной
 *                   комнаты — из него он собирает ссылку.
 * @param gameId Игра, чья это комната; {@code null} — самостоятельная.
 * @param owner Открывший — владелец комнаты.
 * @param memberCount Сколько человек в составе.
 */
public record NexusResponse(
        UUID id,
        String title,
        UUID ownerId,
        @JsonInclude(JsonInclude.Include.NON_NULL) UUID inviteCode,
        @JsonInclude(JsonInclude.Include.NON_NULL) UUID gameId,
        boolean owner,
        int memberCount,
        Instant createdAt,
        Instant updatedAt
) {
}
