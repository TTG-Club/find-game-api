package club.ttg.findgame.profile.api;

import club.ttg.findgame.profile.Gender;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record UpdateUserProfileRequest(
        @Min(1900) @Max(2100) Integer birthYear,
        Gender gender,
        @Min(0) @Max(100) Integer tabletopExperienceYears,
        @NotNull @Valid MasterProfileRequest master,
        @NotNull @Valid PlayerProfileRequest player
) {
}
