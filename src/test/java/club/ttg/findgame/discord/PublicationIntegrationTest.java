package club.ttg.findgame.discord;

import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static club.ttg.findgame.discord.PublicationModels.*;

/** Проверяет реальные SQL и транзакции настройки и захвата выпусков. */
@SpringJUnitConfig(PublicationIntegrationTest.Configuration.class)
class PublicationIntegrationTest {
    @org.springframework.context.annotation.Configuration
    @EnableTransactionManagement
    static class Configuration {
        @Bean DataSource source() { return new DriverManagerDataSource("jdbc:h2:mem:discord;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""); }
        @Bean JdbcTemplate jdbc(DataSource source) { return new JdbcTemplate(source); }
        @Bean PlatformTransactionManager transactions(DataSource source) { return new DataSourceTransactionManager(source); }
        @Bean ObjectMapper mapper() { return new ObjectMapper(); }
        @Bean WebhookSecrets secrets() { return new WebhookSecrets("", "0123456789abcdef0123456789abcdef"); }
        @Bean PublicationStore store(JdbcTemplate jdbc, ObjectMapper mapper) { return new PublicationStore(jdbc, mapper); }
        @Bean PublicationService service(PublicationStore store, WebhookSecrets secrets) { return new PublicationService(store, secrets); }
        @Bean GameDigest digest(JdbcTemplate jdbc) { return new GameDigest(jdbc, "https://ttg.club"); }
        @Bean DiscordWebhookClient client() { return mock(DiscordWebhookClient.class); }
        @Bean PublicationTestSender testSender(PublicationService service, WebhookSecrets secrets, DiscordWebhookClient client) {
            return new PublicationTestSender(service, secrets, client);
        }
    }
    @Autowired DataSource source;
    @Autowired JdbcTemplate jdbc;
    @Autowired PublicationService service;
    @Autowired GameDigest digest;
    @Autowired WebhookSecrets secrets;
    @Autowired DiscordWebhookClient client;
    @Autowired PublicationTestSender testSender;
    private static final List<Slot> GLOBAL = List.of(new Slot(1, "18:00"), new Slot(4, "20:00"));
    private static final String WEBHOOK = "https://discord.com/api/webhooks/123456789012345678/" + "a".repeat(60);

    /** Выполняет настоящую миграцию на чистой тестовой базе. */
    @BeforeEach void prepare() {
        reset(client);
        jdbc.execute("drop all objects");
        new ResourceDatabasePopulator(new ClassPathResource("db/changelog/changes/057-discord-publications.sql")).execute(source);
        jdbc.execute("create table game_systems (code varchar primary key, name varchar)");
        jdbc.execute("create table games (id uuid primary key, title varchar, game_system varchar, custom_system varchar, max_players integer, list_position_at timestamp with time zone, status varchar, visibility varchar, deleted_at timestamp with time zone, recruitment_closed boolean)");
        jdbc.execute("create table game_registrations (id uuid primary key, game_id uuid, status varchar)");
        jdbc.update("insert into game_systems values ('DND', 'D&D'), ('PATHFINDER', 'Pathfinder'), ('HOMEBREW', 'Своя система')");
    }

    /** Индивидуальный график сохраняется при смене общего, а мастер-выключатель останавливает всех. */
    @Test void inheritanceAndMasterSwitch() {
        service.saveSettings(new SettingsInput(true, GLOBAL, 0));
        Channel inherited = addChannel("Общий", null, WEBHOOK);
        List<Slot> override = List.of(new Slot(2, "12:00"));
        Channel individual = addChannel("Личный", override, WEBHOOK.replace("aaaa", "bbbb"));
        Instant previousNext = individual.nextRunAt();
        Overview changed = service.saveSettings(new SettingsInput(true, List.of(new Slot(7, "09:00")), 1));
        assertThat(find(changed, individual.id()).nextRunAt()).isEqualTo(previousNext);
        assertThat(find(changed, inherited.id()).nextRunAt()).isNotEqualTo(inherited.nextRunAt());
        Overview disabled = service.saveSettings(new SettingsInput(false, GLOBAL, 2));
        assertThat(disabled.channels()).allSatisfy(channel -> assertThat(channel.nextRunAt()).isNull());
        assertThat(service.claim(Instant.now().plusSeconds(604800))).isNull();
    }

    /** Две реплики захватывают один выпуск ровно один раз; рестарт не отправляет его повторно. */
    @Test void concurrentClaimAndRecovery() throws Exception {
        service.saveSettings(new SettingsInput(true, GLOBAL, 0));
        Channel channel = addChannel("Канал", null, WEBHOOK);
        Instant now = Instant.now();
        due(channel.id(), now.minusSeconds(10));
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            Callable<Delivery> claim = () -> { start.await(); return service.claim(now); };
            Future<Delivery> first = executor.submit(claim);
            Future<Delivery> second = executor.submit(claim);
            start.countDown();
            assertThat(Arrays.asList(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .filteredOn(Objects::nonNull).hasSize(1);
        }
        assertThat(service.history()).hasSize(1);
        assertThat(service.claim(now.plusSeconds(121))).isNull();
        assertThat(service.history().getFirst().status()).isEqualTo("UNKNOWN");
    }

    /** Только явный 429 разрешает повтор, а смена настроек отменяет его. */
    @Test void retryAndCancellation() {
        service.saveSettings(new SettingsInput(true, GLOBAL, 0));
        Channel channel = addChannel("Канал", null, WEBHOOK);
        Instant now = Instant.now();
        due(channel.id(), now.minusSeconds(10));
        Delivery first = service.claim(now);
        service.finish(first, new Outcome("RETRY", "Лимит", null, now.plusSeconds(30)), 3, now);
        assertThat(service.claim(now.plusSeconds(29))).isNull();
        Delivery second = service.claim(now.plusSeconds(31));
        assertThat(second.runId()).isEqualTo(first.runId());
        assertThat(second.attempts()).isEqualTo(2);
        service.finish(second, new Outcome("RETRY", "Лимит", null, now.plusSeconds(60)), 3, now.plusSeconds(31));
        service.saveChannel(channel.id(), new ChannelInput("Новое имя", false, "", null, channel.revision()));
        assertThat(service.history().getFirst().status()).isEqualTo("SKIPPED");
        assertThat(service.claim(now.plusSeconds(61))).isNull();
    }

    /** Удаление оставляет журнал; старый снимок не может перезаписать новые настройки. */
    @Test void revisionDeletionAndSecretProtection() {
        service.saveSettings(new SettingsInput(true, GLOBAL, 0));
        Channel channel = addChannel("Канал", null, WEBHOOK);
        String storedSecret = jdbc.queryForObject("select webhook_secret from discord_publication_channels", String.class);
        assertThat(service.overview().configured()).isTrue();
        assertThat(storedSecret).startsWith("hkdf-v1:").doesNotContain(WEBHOOK, "0123456789abcdef0123456789abcdef");
        assertThat(secrets.decrypt(storedSecret)).isEqualTo(WEBHOOK);
        assertThatThrownBy(() -> addChannel("Повтор", null, WEBHOOK.replace("/api/", "/api/v10/"))).isInstanceOf(ResponseStatusException.class);
        service.saveChannel(channel.id(), new ChannelInput("Изменён", true, "", null, 0));
        assertThatThrownBy(() -> service.saveChannel(channel.id(), new ChannelInput("Устарел", true, "", null, 0))).isInstanceOf(ResponseStatusException.class);
        due(channel.id(), Instant.now().minusSeconds(10));
        Delivery delivery = service.claim(Instant.now());
        service.deleteChannel(channel.id(), 1);
        assertThat(service.current(delivery)).isFalse();
        assertThat(service.history()).hasSize(1);
        assertThat(service.overview().channels()).isEmpty();
    }

    /** Просроченный выпуск отмечается пропущенным без сетевой отправки. */
    @Test void downtimeSkipsBacklog() {
        service.saveSettings(new SettingsInput(true, GLOBAL, 0));
        Channel channel = addChannel("Канал", null, WEBHOOK);
        due(channel.id(), Instant.now().minusSeconds(901));
        assertThat(service.claim(Instant.now())).isNull();
        assertThat(service.history().getFirst().status()).isEqualTo("SKIPPED");
        assertThat(service.overview().channels().getFirst().nextRunAt()).isAfter(Instant.now());
    }

    /** Редкая система попадает в первый круг даже после 30 свежих игр основной системы. */
    @Test void diverseSelectionAndEligibility() {
        Instant now = Instant.now();
        for (int index = 0; index < 30; index++) game("DND", null, now.minusSeconds(index), "OPEN", "PUBLIC", false);
        UUID rare = game("PATHFINDER", null, now.minusSeconds(100), "OPEN", "PUBLIC", false);
        UUID customFirst = game("HOMEBREW", "Моя система", now.minusSeconds(110), "OPEN", "PUBLIC", false);
        UUID customSecond = game("HOMEBREW", "Другая система", now.minusSeconds(120), "OPEN", "PUBLIC", false);
        UUID sameCustom = game("HOMEBREW", " моя СИСТЕМА ", now.minusSeconds(130), "OPEN", "PUBLIC", false);
        UUID privateGame = game("PATHFINDER", null, now.plusSeconds(10), "OPEN", "PRIVATE", false);
        UUID closed = game("PATHFINDER", null, now.plusSeconds(11), "CLOSED", "PUBLIC", false);
        UUID draft = game("PATHFINDER", null, now.plusSeconds(12), "DRAFT", "PUBLIC", false);
        UUID stopped = game("PATHFINDER", null, now.plusSeconds(13), "OPEN", "PUBLIC", true);
        UUID deleted = game("PATHFINDER", null, now.plusSeconds(14), "OPEN", "PUBLIC", false);
        jdbc.update("update games set deleted_at = ? where id = ?", Timestamp.from(now), deleted);
        UUID full = game("PATHFINDER", null, now.plusSeconds(15), "OPEN", "PUBLIC", false);
        for (int index = 0; index < 4; index++) jdbc.update("insert into game_registrations values (?, ?, 'PENDING')", UUID.randomUUID(), full);
        jdbc.update("insert into game_registrations values (?, ?, 'REJECTED'), (?, ?, 'APPROVED')", UUID.randomUUID(), rare, UUID.randomUUID(), rare);
        List<GameEntry> selected = digest.preview();
        assertThat(selected).hasSize(20);
        assertThat(selected.subList(0, 4)).extracting(GameEntry::id).contains(rare, customFirst, customSecond).doesNotContain(sameCustom);
        assertThat(selected).extracting(GameEntry::id).doesNotContain(privateGame, closed, draft, stopped, deleted, full);
        assertThat(selected.stream().filter(game -> game.id().equals(rare)).findFirst().orElseThrow().takenSeats()).isEqualTo(1);
        assertThat(selected).allSatisfy(game -> assertThat(game.url()).isEqualTo("https://ttg.club/games/" + game.id()));
    }

    /** Небольшой каталог публикуется без искусственного заполнения до двадцати. */
    @Test void emptyAndSmallCatalogue() {
        assertThat(digest.preview()).isEmpty();
        game("DND", null, Instant.now(), "OPEN", "PUBLIC", false);
        assertThat(digest.preview()).hasSize(1);
    }

    /** Исчерпание попыток не снимает общий лимит для остальных каналов. */
    @Test void exhaustedRetryStillPausesOtherChannels() {
        service.saveSettings(new SettingsInput(true, GLOBAL, 0));
        Channel first = addChannel("Первый", null, WEBHOOK);
        Channel second = addChannel("Второй", null, WEBHOOK.replace("aaaa", "bbbb"));
        Instant now = Instant.now();
        due(first.id(), now.minusSeconds(20));
        due(second.id(), now.minusSeconds(10));
        Delivery claimed = service.claim(now);
        Delivery finalAttempt = new Delivery(claimed.runId(), claimed.channelId(), claimed.channelRevision(), claimed.secret(), claimed.scheduledAt(), 3);
        service.finish(finalAttempt, new Outcome("RETRY", "Лимит", null, now.plusSeconds(60)), 2, now);
        assertThat(service.history().getFirst().status()).isEqualTo("FAILED");
        assertThat(service.claim(now.plusSeconds(59))).isNull();
        assertThat(service.claim(now.plusSeconds(61)).channelId()).isEqualTo(second.id());
    }

    /** Сохранённый выключенный канал проверяется до включения расписания; HTTP не удерживает транзакцию. */
    @Test void testSendsSavedSecretWithoutEnablingOrChangingSchedule() {
        Channel channel = service.saveChannel(null, new ChannelInput("Тестовый канал", false, WEBHOOK, null, 0)).channels().getFirst();
        Overview before = service.overview();
        when(client.send(eq(WEBHOOK), anyMap())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            Map<String, Object> payload = invocation.getArgument(1);
            assertThat(payload).containsEntry("allowed_mentions", Map.of("parse", List.of())).doesNotContainKey("embeds");
            assertThat(payload.get("content").toString()).contains("Тестовое сообщение").doesNotContain(WEBHOOK);
            return new Outcome("SENT", "Опубликовано", "123456789012345678", null);
        });
        assertThat(testSender.send(channel.id(), channel.revision()).status()).isEqualTo("SENT");
        assertThat(service.overview()).isEqualTo(before);
        assertThat(service.history()).singleElement().satisfies(run -> {
            assertThat(run.status()).isEqualTo("SENT");
            assertThat(run.detail()).startsWith("Тест:");
            assertThat(run.gameCount()).isZero();
        });
        assertThatThrownBy(() -> testSender.send(channel.id(), channel.revision())).isInstanceOf(ResponseStatusException.class);
        verify(client, times(1)).send(eq(WEBHOOK), anyMap());
    }

    /** Пробная отправка сохраняет дату плановой публикации, а устаревшие и удалённые каналы не отправляются. */
    @Test void testPreservesNextRunAndRejectsChangedChannels() {
        service.saveSettings(new SettingsInput(true, GLOBAL, 0));
        Channel channel = addChannel("Канал", null, WEBHOOK);
        Overview before = service.overview();
        Delivery delivery = service.claimTest(channel.id(), channel.revision(), Instant.now());
        assertThat(service.overview()).isEqualTo(before);
        service.saveChannel(channel.id(), new ChannelInput("Новое имя", true, "", null, channel.revision()));
        assertThat(service.currentTest(delivery)).isFalse();
        assertThatThrownBy(() -> testSender.send(channel.id(), channel.revision())).isInstanceOf(ResponseStatusException.class);
        service.deleteChannel(channel.id(), channel.revision() + 1);
        assertThat(service.currentTest(delivery)).isFalse();
        assertThatThrownBy(() -> testSender.send(channel.id(), channel.revision())).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(client);
    }

    /** Ограничение Discord запрещает повтор теста и учитывается планировщиком на всех каналах. */
    @Test void testRateLimitNeverCreatesScheduledRetry() {
        service.saveSettings(new SettingsInput(true, GLOBAL, 0));
        Channel channel = addChannel("Канал", null, WEBHOOK);
        Instant retryAt = Instant.now().plusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        when(client.send(eq(WEBHOOK), anyMap())).thenReturn(new Outcome("RETRY", "Лимит", null, retryAt));
        assertThat(testSender.send(channel.id(), channel.revision()).status()).isEqualTo("FAILED");
        assertThat(service.overview().settings().pausedUntil()).isEqualTo(retryAt);
        assertThat(service.history().getFirst().status()).isEqualTo("FAILED");
        due(channel.id(), retryAt.plusSeconds(3600));
        assertThat(service.claim(retryAt.plusSeconds(1))).isNull();
        Channel other = addChannel("Другой", null, WEBHOOK.replace("aaaa", "bbbb"));
        assertThatThrownBy(() -> testSender.send(other.id(), other.revision())).isInstanceOf(ResponseStatusException.class);
        verify(client, times(1)).send(eq(WEBHOOK), anyMap());
    }

    /** Ошибки и неоднозначная доставка видны в журнале и не превращаются в успех. */
    @Test void testErrorsStaySafeAndDoNotRetry() {
        Channel first = addChannel("Первый", null, WEBHOOK);
        when(client.send(eq(WEBHOOK), anyMap())).thenReturn(new Outcome("FAILED", "Discord отклонил отправку (HTTP 404)", null, null));
        assertThat(testSender.send(first.id(), first.revision()).status()).isEqualTo("FAILED");
        String otherWebhook = WEBHOOK.replace("aaaa", "bbbb");
        Channel second = addChannel("Второй", null, otherWebhook);
        when(client.send(eq(otherWebhook), anyMap())).thenThrow(new IllegalStateException(otherWebhook));
        TestResult uncertain = testSender.send(second.id(), second.revision());
        assertThat(uncertain.status()).isEqualTo("UNKNOWN");
        assertThat(uncertain.detail()).doesNotContain(otherWebhook);
        assertThat(service.history()).extracting(Run::status).containsExactlyInAnyOrder("FAILED", "UNKNOWN");
        assertThat(service.claim(Instant.now().plusSeconds(121))).isNull();
        verify(client, times(2)).send(anyString(), anyMap());
    }

    /** Конкурирующие запросы с разных реплик создают один тест; после сбоя не повторяется доставка. */
    @Test void concurrentTestsAndInterruptedTestRecovery() throws Exception {
        Channel channel = addChannel("Канал", null, WEBHOOK);
        Instant now = Instant.now();
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            Callable<Boolean> claim = () -> {
                start.await();
                try { service.claimTest(channel.id(), channel.revision(), now); return true; }
                catch (ResponseStatusException exception) {
                    assertThat(exception.getStatusCode().value()).isEqualTo(429);
                    return false;
                }
            };
            Future<Boolean> first = executor.submit(claim);
            Future<Boolean> second = executor.submit(claim);
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
        }
        assertThat(service.history()).hasSize(1);
        service.claim(now.plusSeconds(121));
        assertThat(service.history().getFirst().status()).isEqualTo("UNKNOWN");
        assertThat(service.history().getFirst().detail()).startsWith("Тест:");
        assertThat(service.claimTest(channel.id(), channel.revision(), now.plusSeconds(122))).isNotNull();
        verifyNoInteractions(client);
    }

    private Channel addChannel(String name, List<Slot> schedule, String webhook) {
        return service.saveChannel(null, new ChannelInput(name, true, webhook, schedule, 0)).channels().stream()
                .filter(channel -> channel.name().equals(name)).findFirst().orElseThrow();
    }
    private Channel find(Overview overview, UUID channelId) { return overview.channels().stream().filter(channel -> channel.id().equals(channelId)).findFirst().orElseThrow(); }
    private void due(UUID channelId, Instant instant) { jdbc.update("update discord_publication_channels set next_run_at = ? where id = ?", Timestamp.from(instant), channelId); }
    private UUID game(String system, String customSystem, Instant position, String status, String visibility, boolean stopped) {
        UUID gameId = UUID.randomUUID();
        jdbc.update("insert into games values (?, ?, ?, ?, 4, ?, ?, ?, null, ?)", gameId, "Игра " + gameId, system, customSystem, Timestamp.from(position), status, visibility, stopped);
        return gameId;
    }
}
