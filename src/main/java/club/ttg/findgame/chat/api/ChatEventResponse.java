package club.ttg.findgame.chat.api;

import club.ttg.findgame.chat.ChatEventType;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * Событие ленты комнаты: сообщение, бросок, применённое заклинание или
 * системная отметка.
 */
public record ChatEventResponse(
        UUID id,
        UUID nexusId,
        UUID authorId,
        UUID clientMessageId,
        ChatEventType type,
        String text,
        JsonNode payload,
        Instant createdAt
) {
}
