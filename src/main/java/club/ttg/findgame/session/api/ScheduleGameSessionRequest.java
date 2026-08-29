package club.ttg.findgame.session.api;

import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * Назначение даты сессии, объявленной с открытой датой.
 *
 * В отличие от создания дата здесь обязательна: смысл запроса — как раз
 * закрыть открытую дату. Снять её обратно нельзя: игроки уже подстроились
 * под объявленное время.
 */
public record ScheduleGameSessionRequest(
        @NotNull @FutureOrPresent Instant startsAt
) {
}
