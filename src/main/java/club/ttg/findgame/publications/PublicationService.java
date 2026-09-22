package club.ttg.findgame.publications;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import static club.ttg.findgame.publications.PublicationModels.*;

/** Управляет настройками и атомарным переходом выпуска к отправке. */
@Service
public class PublicationService {
    private final PublicationStore store;
    private final PublicationSecrets secrets;
    private final PublicationSender sender;

    /** Подключает хранилище и серверное шифрование. */
    public PublicationService(PublicationStore store, PublicationSecrets secrets, PublicationSender sender) {
        this.store = store; this.secrets = secrets; this.sender = sender;
    }

    /** Возвращает согласованный снимок настроек без адресов каналов. */
    @Transactional
    public Overview overview() {
        Settings settings = store.settings(true);
        return new Overview(settings, store.channels().stream().map(StoredChannel::channel).toList(),
                secrets.configured(), WeeklySchedule.ZONE.getId(), sender.configured(Platform.TELEGRAM),
                sender.configured(Platform.VK), !sender.defaultAddress(Platform.VK).isBlank());
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
                        channel.revision() + 1, next, channel.platform(), channel.imageUrl()), stored.secret(), stored.fingerprint());
                store.cancelRetries(channel.id(), now);
            }
        }
        return overview();
    }

    /** Создаёт или изменяет канал; пустое поле оставляет прежний секрет, отсутствие imageUrl — прежнюю картинку. */
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
        // Отсутствие platform поддерживает сохранённый контракт Discord и не меняет тип существующего канала.
        Platform platform = input.platform() == null ? previous == null ? Platform.DISCORD : previous.channel().platform() : input.platform();
        if (previous != null && previous.channel().platform() != platform) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Платформу существующего канала изменить нельзя; добавьте новый канал");
        }
        String address = input.address(platform);
        for (Platform other : Platform.values()) {
            String unrelatedAddress = input.address(other);
            if (other != platform && unrelatedAddress != null && !unrelatedAddress.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Адрес канала не соответствует выбранной платформе");
            }
        }
        boolean replaceSecret = address != null && !address.isBlank();
        // Новый канал без адреса берёт сообщество из VK_GROUP_ID, как новости core-api; ID запоминается при создании.
        if (!replaceSecret && previous == null) address = sender.defaultAddress(platform);
        String destination = replaceSecret || previous == null ? secrets.normalize(platform, address) : null;
        String fingerprint = destination == null ? previous.fingerprint() : secrets.fingerprint(platform, destination);
        if (channels.stream().anyMatch(stored -> stored.fingerprint().equals(fingerprint)
                && !stored.channel().id().equals(channelId))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Этот адрес канала уже добавлен");
        }
        String secret = destination == null ? previous.secret() : secrets.encrypt(platform, destination);
        // Прежние клиенты не знают о картинке и не передают поле: оно не должно стирать выбранную картинку.
        String imageUrl = input.imageUrl() == null ? previous == null ? null : previous.channel().imageUrl()
                : input.imageUrl().isBlank() ? null : PublicationImages.normalize(input.imageUrl());
        Instant now = Instant.now();
        List<Slot> schedule = input.schedule() == null ? settings.schedule() : input.schedule();
        Instant next = settings.enabled() && input.enabled() ? WeeklySchedule.next(schedule, now) : null;
        Channel channel = new Channel(channelId == null ? UUID.randomUUID() : channelId, input.name().trim(),
                input.enabled(), input.schedule(), previous == null ? 0 : previous.channel().revision() + 1, next, platform, imageUrl);
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

    /** Ручная проверка работает до включения графика и не меняет его состояние. */
    @Transactional
    public Delivery claimTest(UUID channelId, long revision, Instant now) {
        store.settings(true);
        StoredChannel stored = requireChannel(store.channels(), channelId);
        checkRevision(revision, stored.channel().revision());
        if (!sender.configured(stored.channel().platform())) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Отправка в выбранную платформу не настроена на сервере");
        }
        store.recover(now);
        if (store.paused(stored.channel().platform(), now) || store.testBlocked(channelId, now)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Подождите перед повторной проверкой канала");
        }
        return store.claimTest(stored, now);
    }

    /** Отменяет ещё не отправленный тест, если канал удалён или изменён. */
    @Transactional
    public boolean currentTest(Delivery delivery) {
        store.settings(true);
        return sender.configured(delivery.platform()) && !store.paused(delivery.platform(), Instant.now())
                && store.channels().stream().anyMatch(stored -> stored.channel().id().equals(delivery.channelId())
                && stored.channel().revision() == delivery.channelRevision());
    }

    /** Сохраняет тест без автоматического повтора, учитывая лимит выбранной платформы. */
    @Transactional
    public TestResult finishTest(Delivery delivery, Outcome outcome, Instant now) {
        store.settings(true);
        if (outcome.retryAt() != null) store.pauseUntil(delivery.platform(), outcome.retryAt());
        String status = outcome.status().equals("RETRY") ? "FAILED" : outcome.status();
        String detail = outcome.status().equals("RETRY")
                ? "Платформа ограничила частоту отправки; попробуйте позже" : outcome.detail();
        store.finish(delivery, new Outcome(status, "Тест: " + detail, outcome.messageId(), null), 0, now);
        return new TestResult(status, detail);
    }

    /** Захватывает один выпуск в короткой транзакции; повторный захват другим сервером невозможен. */
    @Transactional
    public Delivery claim(Instant now) {
        Settings settings = store.settings(true);
        store.recover(now);
        if (!secrets.configured() || !settings.enabled()) return null;
        EnumSet<Platform> available = EnumSet.noneOf(Platform.class);
        for (Platform platform : Platform.values()) {
            if (sender.configured(platform) && !store.paused(platform, now)) available.add(platform);
        }
        for (Platform platform : available) {
            Delivery retry = store.retry(now, platform);
            if (retry != null) return retry;
        }
        List<StoredChannel> dueChannels = store.channels().stream().filter(stored -> stored.channel().enabled()
                && available.contains(stored.channel().platform())
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
        return settings.enabled() && sender.configured(delivery.platform())
                && store.channels().stream().anyMatch(stored ->
                stored.channel().id().equals(delivery.channelId()) && stored.channel().enabled()
                && stored.channel().revision() == delivery.channelRevision());
    }

    /** Фиксирует результат и ограничивает повторы только явным ответом 429. */
    @Transactional
    public void finish(Delivery delivery, Outcome outcome, int gameCount, Instant now) {
        store.settings(true);
        if (outcome.retryAt() != null) store.pauseUntil(delivery.platform(), outcome.retryAt());
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
    private static void unavailable() { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Не настроено шифрование адресов каналов"); }
}
