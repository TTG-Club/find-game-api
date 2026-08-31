package club.ttg.findgame.registration.api;

import club.ttg.findgame.registration.SessionRegistrationStatus;
import club.ttg.findgame.registration.SessionAttendanceStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

public record SessionRegistrationResponse(
        UUID id,
        UUID sessionId,
        UUID playerId,
        @JsonInclude(JsonInclude.Include.NON_NULL) String characterSheetUrl,
        @JsonInclude(JsonInclude.Include.NON_NULL) String characterName,
        SessionRegistrationStatus status,
        @JsonInclude(JsonInclude.Include.NON_NULL) SessionAttendanceStatus attendanceStatus,
        boolean paid,
        @JsonInclude(JsonInclude.Include.NON_NULL) Instant paidAt,
        Instant createdAt,
        Instant updatedAt
) {
}
