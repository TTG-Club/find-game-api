package club.ttg.findgame.session.api;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * Правка назначенной встречи: название, время и длительность.
 *
 * Оплата сюда не входит: по ней уже могут быть расчёты с игроками, и её смена
 * задним числом меняла бы условия, на которые они соглашались.
 */
public record UpdateGameSessionRequest(
        @NotBlank @Size(max = 150) String title,
        @NotNull @Future(message = "Дата и время начала сессии должны быть в будущем") Instant startsAt,
        @Positive Integer estimatedDurationMinutes
) {
}
