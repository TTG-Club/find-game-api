package club.ttg.findgame.session.api;

import club.ttg.findgame.session.SessionPaymentType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;

/**
 * Серия сессий по расписанию: «по средам и пятницам в 19:00 до конца ноября».
 *
 * Кампания идёт неделями, и заводить каждую встречу вручную мастеру незачем.
 * Горизонт задаётся конечной датой, а не числом недель: «десять недель» и
 * «два месяца» — это одно и то же поле, и считать их лучше там, где мастер их
 * и называет.
 *
 * @param title Общее название встреч; различает их дата.
 * @param startsOn Первый день, с которого искать подходящие дни недели.
 * @param until Последний день серии включительно.
 * @param daysOfWeek Дни недели, по которым проводятся встречи.
 * @param timeOfDay Время начала в поясе мастера.
 * @param zoneId Пояс мастера: «19:00 по средам» не должно ехать при переходе
 *               на летнее время.
 */
public record CreateGameSessionSeriesRequest(
        @NotBlank @Size(max = 150) String title,
        @NotNull @FutureOrPresent LocalDate startsOn,
        @NotNull LocalDate until,
        @NotEmpty Set<DayOfWeek> daysOfWeek,
        @NotNull LocalTime timeOfDay,
        @NotBlank String zoneId,
        @Positive Integer estimatedDurationMinutes,
        @DecimalMin(value = "0.01") @Digits(integer = 10, fraction = 2) BigDecimal priceAmount,
        @Pattern(regexp = "[A-Z]{3}", message = "должно содержать трёхбуквенный код валюты ISO 4217")
        String priceCurrency,
        SessionPaymentType paymentType
) {
}
