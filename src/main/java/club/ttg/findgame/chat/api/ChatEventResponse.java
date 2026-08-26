package club.ttg.findgame.chat.api;

import club.ttg.findgame.chat.ChatEventType;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public record ChatEventResponse(
        UUID id,
        UUID gameId,
        UUID sessionId,
        UUID authorId,
        UUID clientMessageId,
        ChatEventType type,
        String text,
        JsonNode payload,
        Instant createdAt
) {
}
