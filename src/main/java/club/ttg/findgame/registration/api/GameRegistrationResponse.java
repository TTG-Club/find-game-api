package club.ttg.findgame.registration.api;

import club.ttg.findgame.registration.RegistrationStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

public record GameRegistrationResponse(
        UUID id,
        UUID gameId,
        UUID playerId,
        @JsonInclude(JsonInclude.Include.NON_NULL) String characterSheetUrl,
        @JsonInclude(JsonInclude.Include.NON_NULL) String characterName,
        RegistrationStatus status,
        Instant createdAt,
        Instant updatedAt
) {
}
