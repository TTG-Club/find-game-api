package club.ttg.findgame.chat.api;

import club.ttg.findgame.chat.ChatEventType;
import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public record ChatEventResponse(
        UUID id,
        UUID gameId,
        UUID sessionId,
        /** Собеседник мастера в личной переписке; пусто в остальных лентах. */
        @JsonInclude(JsonInclude.Include.NON_NULL) UUID playerId,
        UUID authorId,
        UUID clientMessageId,
        ChatEventType type,
        String text,
        JsonNode payload,
        Instant createdAt
) {
}
