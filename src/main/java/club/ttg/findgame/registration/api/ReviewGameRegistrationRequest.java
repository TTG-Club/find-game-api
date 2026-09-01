package club.ttg.findgame.registration.api;

import club.ttg.findgame.registration.RegistrationDecision;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Решение мастера по заявке.
 *
 * @param decision Принять или отклонить.
 * @param reason Причина отказа: необязательна и осмысленна только при отказе.
 */
public record ReviewGameRegistrationRequest(
        @NotNull RegistrationDecision decision,
        @Size(max = 500) String reason
) {
}
