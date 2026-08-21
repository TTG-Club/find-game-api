package club.ttg.findgame.profile.api;

import jakarta.validation.constraints.Size;

public record MasterProfileRequest(
        @Size(max = 5000) String about
) {
}
