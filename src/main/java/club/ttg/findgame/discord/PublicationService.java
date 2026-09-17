package club.ttg.findgame.discord;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import static club.ttg.findgame.discord.PublicationModels.*;

/** Управляет настройками и атомарным переходом выпуска к отправке. */
@Service
public class PublicationService {
    private final PublicationStore store;
    private final WebhookSecrets secrets;

    /** Подключает хранилище и серверное шифрование. */
    public PublicationService(PublicationStore store, WebhookSecrets secrets) { this.store = store; this.secrets = secrets; }

    /** Возвращает согласованный снимок настроек без вебхуков. */
    @Transactional
    public Overview overview() {
        Settings settings = store.settings(true);
        return new Overview(settings, store.channels().stream().map(StoredChannel::channel).toList(),
                secrets.configured(), WeeklySchedule.ZONE.getId());
    }

    /** Меняет общий график и пересчитывает только нужные каналы. */
    @Transactional
    public Overview saveSettings(SettingsInput input) {
        WeeklySchedule.validate(input.schedule(), input.enabled());
        Settings previous = store.settings(true);
        checkRevision(input.revision(), previous.revision());
        if (input.enabled() && !secrets.configured()) unavailable();
        store.saveSettings(input);
        Instant now = Instant.now();
        for (StoredChannel stored : store.channels()) {
            Channel channel = stored.channel();
            if (channel.schedule() == null || previous.enabled() != input.enabled()) {
                List<Slot> schedule = channel.schedule() == null ? input.schedule() : channel.schedule();
                Instant next = input.enabled() && channel.enabled() ? WeeklySchedule.next(schedule, now) : null;
                store.updateChannel(new Channel(channel.id(), channel.name(), channel.enabled(), channel.schedule(),
                        channel.revision() + 1, next), stored.secret(), stored.fingerprint());
                store.cancelRetries(channel.id(), now);
            }
        }
        return overview();
    }

    /** Создаёт или изменяет канал; пустое поле оставляет прежний секрет. */
    @Transactional
    public Overview saveChannel(UUID channelId, ChannelInput input) {
        Settings settings = store.settings(true);
        if (!secrets.configured()) unavailable();
        if (input.schedule() != null) WeeklySchedule.validate(input.schedule(), true);
        List<StoredChannel> channels = store.channels();
        StoredChannel previous = channelId == null ? null : requireChannel(channels, channelId);
        if (previous != null) checkRevision(input.revision(), previous.channel().revision());
        if (previous == null && channels.size() >= 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Можно настроить до 100 каналов");
        }
        boolean replaceSecret = input.webhookUrl() != null && !input.webhookUrl().isBlank();
        String webhook = replaceSecret || previous == null ? secrets.normalize(input.webhookUrl()) : null;
        String fingerprint = webhook == null ? previous.fingerprint() : secrets.fingerprint(webhook);
        if (channels.stream().anyMatch(stored -> stored.fingerprint().equals(fingerprint)
                && !stored.channel().id().equals(channelId))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Этот вебхук уже добавлен");
        }
        String secret = webhook == null ? previous.secret() : secrets.encrypt(webhook);
        Instant now = Instant.now();
        List<Slot> schedule = input.schedule() == null ? settings.schedule() : input.schedule();
        Instant next = settings.enabled() && input.enabled() ? WeeklySchedule.next(schedule, now) : null;
        Channel channel = new Channel(channelId == null ? UUID.randomUUID() : channelId, input.name().trim(),
                input.enabled(), input.schedule(), previous == null ? 0 : previous.channel().revision() + 1, next);
        if (previous == null) store.insertChannel(channel, secret, fingerprint);
        else {
            store.updateChannel(channel, secret, fingerprint);
            store.cancelRetries(channel.id(), now);
        }
        return overview();
    }

    /** Удаляет канал с проверкой версии, не удаляя журнал. */
    @Transactional
    public Overview deleteChannel(UUID channelId, long revision) {
        store.settings(true);
        Channel channel = requireChannel(store.channels(), channelId).channel();
        checkRevision(revision, channel.revision());
        store.cancelRetries(channelId, Instant.now());
        store.deleteChannel(channelId);
        return overview();
    }

    /** Захватывает один выпуск в короткой транзакции; повторный захват другим сервером невозможен. */
    @Transactional
    public Delivery claim(Instant now) {
        Settings settings = store.settings(true);
        store.recover(now);
        if (!secrets.configured() || !settings.enabled()
                || (settings.pausedUntil() != null && settings.pausedUntil().isAfter(now))) return null;
        Delivery retry = store.retry(now);
        if (retry != null) return retry;
        List<StoredChannel> dueChannels = store.channels().stream().filter(stored -> stored.channel().enabled()
                && stored.channel().nextRunAt() != null && !stored.channel().nextRunAt().isAfter(now))
                .sorted(Comparator.comparing(stored -> stored.channel().nextRunAt())).toList();
        for (StoredChannel due : dueChannels) {
            Channel channel = due.channel();
            List<Slot> schedule = channel.schedule() == null ? settings.schedule() : channel.schedule();
            boolean expired = channel.nextRunAt().isBefore(now.minusSeconds(900));
            Delivery delivery = store.claim(channel, due.secret(), WeeklySchedule.next(schedule, now), now, expired);
            if (!expired) return delivery;
        }
        return null;
    }

    /** Перед HTTP повторно проверяет удаление, остановку и смену настроек. */
    @Transactional
    public boolean current(Delivery delivery) {
        Settings settings = store.settings(true);
        return settings.enabled() && store.channels().stream().anyMatch(stored ->
                stored.channel().id().equals(delivery.channelId()) && stored.channel().enabled()
                && stored.channel().revision() == delivery.channelRevision());
    }

    /** Фиксирует результат и ограничивает повторы только явным ответом 429. */
    @Transactional
    public void finish(Delivery delivery, Outcome outcome, int gameCount, Instant now) {
        store.settings(true);
        if (outcome.retryAt() != null) store.pauseUntil(outcome.retryAt());
        if (outcome.status().equals("RETRY") && (!current(delivery) || delivery.attempts() >= 3
                || !outcome.retryAt().isBefore(delivery.scheduledAt().plusSeconds(900)))) {
            outcome = new Outcome("FAILED", "Повтор отложен за пределы окна или настройки изменены", null, null);
        }
        store.finish(delivery, outcome, gameCount, now);
    }

    /** Возвращает безопасный журнал последних выпусков. */
    public List<Run> history() { return store.history(); }

    /** Отклоняет действия над удалённым каналом. */
    private static StoredChannel requireChannel(List<StoredChannel> channels, UUID channelId) {
        return channels.stream().filter(stored -> stored.channel().id().equals(channelId)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Канал удалён"));
    }
    /** Не перезаписывает изменения другого администратора. */
    private static void checkRevision(long expected, long actual) {
        if (expected != actual) throw new ResponseStatusException(HttpStatus.CONFLICT, "Настройки уже изменены; обновите страницу");
    }
    /** Сообщает об отсутствующем ключе без раскрытия конфигурации. */
    private static void unavailable() { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Не настроен ключ шифрования Discord"); }
}
