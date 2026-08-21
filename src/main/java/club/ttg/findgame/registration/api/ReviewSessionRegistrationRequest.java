package club.ttg.findgame.registration.api;

import club.ttg.findgame.registration.RegistrationDecision;
import jakarta.validation.constraints.NotNull;

public record ReviewSessionRegistrationRequest(
        @NotNull RegistrationDecision decision
) {
}
