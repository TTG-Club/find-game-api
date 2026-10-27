package club.ttg.findgame.review;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/** Журнал отправленных напоминаний об оценке: одно на человека в игре. */
@Repository
public class ReviewReminderStore {

    private final JdbcTemplate jdbc;

    public ReviewReminderStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Застолбить напоминание. Строку вставляет только первая из реплик, поэтому
     * человек получает напоминание один раз, сколько бы планировщиков ни
     * работало.
     *
     * @return Застолбили ли сейчас: false — напоминание уже было.
     */
    boolean claim(UUID gameId, UUID userId, Instant sentAt) {
        return jdbc.update("""
                insert into review_reminders (game_id, user_id, sent_at)
                values (?, ?, ?)
                on conflict do nothing
                """, gameId, userId, Timestamp.from(sentAt)) > 0;
    }
}
