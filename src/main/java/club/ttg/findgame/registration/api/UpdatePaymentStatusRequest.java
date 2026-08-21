package club.ttg.findgame.registration.api;

import jakarta.validation.constraints.NotNull;

public record UpdatePaymentStatusRequest(
        @NotNull Boolean paid
) {
}
