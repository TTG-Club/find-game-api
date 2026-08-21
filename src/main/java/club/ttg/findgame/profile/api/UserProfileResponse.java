package club.ttg.findgame.profile.api;

import club.ttg.findgame.profile.Gender;

import java.time.Instant;
import java.util.UUID;

public record UserProfileResponse(
        UUID userId,
        Integer birthYear,
        Gender gender,
        Integer tabletopExperienceYears,
        MasterProfileResponse master,
        PlayerProfileResponse player,
        Instant createdAt,
        Instant updatedAt
) {
}
