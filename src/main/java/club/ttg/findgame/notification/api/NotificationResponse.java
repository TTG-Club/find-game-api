package club.ttg.findgame.notification.api;

import club.ttg.findgame.notification.NotificationType;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

public record NotificationResponse(
        UUID id,
        NotificationType type,
        UUID gameId,
        String gameTitle,
        @JsonInclude(JsonInclude.Include.NON_NULL) UUID sessionId,
        @JsonInclude(JsonInclude.Include.NON_NULL) String sessionTitle,
        @JsonInclude(JsonInclude.Include.NON_NULL) Instant readAt,
        Instant createdAt
) {
}
