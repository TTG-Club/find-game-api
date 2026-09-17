package club.ttg.findgame.discord;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import static club.ttg.findgame.discord.PublicationModels.*;

/** Хранилище расписаний и журнала; HTTP выполняется вне транзакций. */
@Repository
public class PublicationStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    /** Использует существующее подключение приложения. */
    public PublicationStore(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }

    /** Читает настройки; блокировка сериализует изменения и захват выпусков между репликами. */
    Settings settings(boolean lock) {
        return jdbc.queryForObject("select * from discord_publication_settings where id = 1" + (lock ? " for update" : ""),
                (result, rowNumber) -> new Settings(result.getBoolean("enabled"), slots(result.getString("schedule")),
                        result.getLong("revision"), instant(result, "paused_until")));
    }

    /** Возвращает каналы, не раскрывая секрет за пределами сервиса. */
    List<StoredChannel> channels() {
        return jdbc.query("select * from discord_publication_channels order by name, id", (result, rowNumber) ->
                new StoredChannel(new Channel(result.getObject("id", UUID.class), result.getString("name"),
                        result.getBoolean("enabled"), slots(result.getString("schedule")), result.getLong("revision"),
                        instant(result, "next_run_at")), result.getString("webhook_secret"), result.getString("webhook_fingerprint")));
    }

    /** Сохраняет общие настройки под блокировкой. */
    void saveSettings(SettingsInput input) {
        jdbc.update("update discord_publication_settings set enabled = ?, schedule = ?, revision = revision + 1 where id = 1",
                input.enabled(), mapper.writeValueAsString(input.schedule()));
    }

    /** Добавляет новый канал с уже зашифрованным секретом. */
    void insertChannel(Channel channel, String secret, String fingerprint) {
        jdbc.update("""
                insert into discord_publication_channels (id, name, enabled, schedule, revision, next_run_at, webhook_secret, webhook_fingerprint)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """, channel.id(), channel.name(), channel.enabled(), serialize(channel.schedule()), channel.revision(),
                timestamp(channel.nextRunAt()), secret, fingerprint);
    }

    /** Заменяет настройки существующего канала. */
    void updateChannel(Channel channel, String secret, String fingerprint) {
        jdbc.update("""
                update discord_publication_channels set name = ?, enabled = ?, schedule = ?, revision = ?,
                next_run_at = ?, webhook_secret = ?, webhook_fingerprint = ? where id = ?
                """, channel.name(), channel.enabled(), serialize(channel.schedule()), channel.revision(),
                timestamp(channel.nextRunAt()), secret, fingerprint, channel.id());
    }

    /** Удаляет канал, сохраняя историю с его прежним именем. */
    void deleteChannel(UUID channelId) { jdbc.update("delete from discord_publication_channels where id = ?", channelId); }

    /** Отменяет отложенные повторы при изменении настроек канала. */
    void cancelRetries(UUID channelId, Instant now) {
        jdbc.update("""
                update discord_publication_runs set status = 'SKIPPED', detail = 'Настройки изменены', finished_at = ?
                where channel_id = ? and status = 'RETRY'
                """, timestamp(now), channelId);
    }

    /** Фиксирует захват выпуска до сетевого запроса и сразу планирует следующую неделю. */
    Delivery claim(Channel channel, String secret, Instant nextRun, Instant now, boolean expired) {
        UUID runId = UUID.randomUUID();
        jdbc.update("""
                insert into discord_publication_runs
                (id, channel_id, channel_name, channel_revision, scheduled_at, started_at, status, finished_at, detail)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, runId, channel.id(), channel.name(), channel.revision(), timestamp(channel.nextRunAt()), timestamp(now),
                expired ? "SKIPPED" : "SENDING", expired ? timestamp(now) : null, expired ? "Пропущено после простоя более 15 минут" : "");
        jdbc.update("update discord_publication_channels set next_run_at = ? where id = ?", timestamp(nextRun), channel.id());
        return new Delivery(runId, channel.id(), channel.revision(), secret, channel.nextRunAt(), 1);
    }

    /** Захватывает один допустимый повтор после ограничения частоты Discord. */
    Delivery retry(Instant now) {
        List<Delivery> deliveries = jdbc.query("""
                select runs.*, channels.webhook_secret from discord_publication_runs runs
                join discord_publication_channels channels on channels.id = runs.channel_id
                where runs.status = 'RETRY' and runs.retry_at <= ? and channels.enabled = true
                and runs.channel_revision = channels.revision order by runs.retry_at limit 1
                """, (result, rowNumber) -> new Delivery(result.getObject("id", UUID.class), result.getObject("channel_id", UUID.class),
                result.getLong("channel_revision"), result.getString("webhook_secret"), instant(result, "scheduled_at"),
                result.getInt("attempts") + 1), timestamp(now));
        if (deliveries.isEmpty()) return null;
        Delivery delivery = deliveries.getFirst();
        jdbc.update("update discord_publication_runs set status = 'SENDING', started_at = ?, attempts = ? where id = ?",
                timestamp(now), delivery.attempts(), delivery.runId());
        return delivery;
    }

    /** После падения процесса не повторяет запрос, который мог уже доставить сообщение. */
    void recover(Instant now) {
        jdbc.update("""
                update discord_publication_runs set status = 'UNKNOWN', detail = 'Отправка прервана; проверьте канал', finished_at = ?
                where status = 'SENDING' and started_at < ?
                """, timestamp(now), timestamp(now.minusSeconds(120)));
        jdbc.update("""
                update discord_publication_runs set status = 'SKIPPED', detail = 'Истекло окно отправки', finished_at = ?
                where status = 'RETRY' and scheduled_at < ?
                """, timestamp(now), timestamp(now.minusSeconds(900)));
    }

    /** Записывает безопасный результат; поздний ответ может уточнить UNKNOWN. */
    void finish(Delivery delivery, Outcome outcome, int gameCount, Instant now) {
        jdbc.update("""
                update discord_publication_runs set status = ?, detail = ?, message_id = ?, retry_at = ?,
                game_count = ?, finished_at = ? where id = ? and status in ('SENDING', 'UNKNOWN')
                """, outcome.status(), outcome.detail(), outcome.messageId(), timestamp(outcome.retryAt()),
                gameCount, timestamp(now), delivery.runId());
    }

    /** Учитывает лимит Discord для всех реплик, даже если текущий выпуск уже исчерпал попытки. */
    void pauseUntil(Instant until) {
        jdbc.update("""
                update discord_publication_settings set paused_until = ?
                where id = 1 and (paused_until is null or paused_until < ?)
                """, timestamp(until), timestamp(until));
    }

    /** Возвращает последние 50 выпусков без секретов. */
    List<Run> history() {
        return jdbc.query("select * from discord_publication_runs order by scheduled_at desc, id desc limit 50",
                (result, rowNumber) -> new Run(result.getObject("id", UUID.class), result.getString("channel_name"),
                        instant(result, "scheduled_at"), result.getString("status"), result.getInt("game_count"),
                        result.getString("detail"), result.getString("message_id")));
    }

    /** Разбирает только расписание известного внутреннего формата. */
    private List<Slot> slots(String serialized) { return serialized == null ? null : Arrays.asList(mapper.readValue(serialized, Slot[].class)); }
    /** Отличает наследование от заданного расписания. */
    private String serialize(List<Slot> schedule) { return schedule == null ? null : mapper.writeValueAsString(schedule); }
    /** Сохраняет отсутствие даты при остановленном расписании. */
    private static Timestamp timestamp(Instant instant) { return instant == null ? null : Timestamp.from(instant); }
    /** Читает момент времени независимо от часового пояса JDBC. */
    private static Instant instant(ResultSet result, String column) throws SQLException {
        Timestamp timestamp = result.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
