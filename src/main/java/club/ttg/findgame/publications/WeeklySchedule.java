package club.ttg.findgame.publications;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.HashSet;
import java.util.List;
import static club.ttg.findgame.publications.PublicationModels.*;

/** Еженедельное расписание с явной зоной, независимое от времени сервера. */
final class WeeklySchedule {
    static final ZoneId ZONE = ZoneId.of("Europe/Moscow");
    private WeeklySchedule() {}

    /** Проверяет повторы и отсутствие слотов у включённого расписания. */
    static void validate(List<Slot> slots, boolean required) {
        if (slots == null || slots.size() > 14 || (required && slots.isEmpty())
                || new HashSet<>(slots).size() != slots.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Укажите неповторяющиеся дни и время публикации");
        }
    }

    /** Находит ближайший слот строго после указанного момента. */
    static Instant next(List<Slot> slots, Instant after) {
        return slots.stream().map(slot -> {
            ZonedDateTime candidate = after.atZone(ZONE)
                    .with(TemporalAdjusters.nextOrSame(DayOfWeek.of(slot.day())))
                    .with(LocalTime.parse(slot.time()));
            if (!candidate.toInstant().isAfter(after)) candidate = candidate.plusWeeks(1);
            return candidate.toInstant();
        }).min(Instant::compareTo).orElse(null);
    }
}
