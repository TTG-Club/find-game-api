package club.ttg.findgame.registration.api;

import club.ttg.findgame.registration.SessionAttendanceStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

/**
 * Участие игрока в сессии: только то, что относится к самой встрече. Состав
 * определяет заявка в игру, поэтому статуса здесь нет.
 */
public record SessionParticipantResponse(
        UUID id,
        UUID sessionId,
        UUID playerId,
        @JsonInclude(JsonInclude.Include.NON_NULL) SessionAttendanceStatus attendanceStatus,
        boolean paid,
        @JsonInclude(JsonInclude.Include.NON_NULL) Instant paidAt,
        Instant createdAt,
        Instant updatedAt
) {
}
