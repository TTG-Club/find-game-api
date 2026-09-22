package club.ttg.findgame.publications;

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
import static club.ttg.findgame.publications.PublicationModels.*;

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
        @Bean PublicationSecrets secrets() { return new PublicationSecrets("", "0123456789abcdef0123456789abcdef"); }
        @Bean PublicationStore store(JdbcTemplate jdbc, ObjectMapper mapper) { return new PublicationStore(jdbc, mapper); }
        @Bean PublicationService service(PublicationStore store, PublicationSecrets secrets, PublicationSender sender, PublicationImages images) {
            return new PublicationService(store, secrets, sender, images);
        }
        @Bean GameDigest digest(JdbcTemplate jdbc) { return new GameDigest(jdbc, "https://new.ttg.club"); }
        @Bean DiscordWebhookClient client() { return mock(DiscordWebhookClient.class); }
        @Bean TelegramBotClient telegram() { return mock(TelegramBotClient.class); }
        @Bean VkWallClient vk() { return mock(VkWallClient.class); }
        @Bean PublicationSender sender(PublicationSecrets secrets, DiscordWebhookClient client, TelegramBotClient telegram, VkWallClient vk) {
            return new PublicationSender(secrets, client, telegram, vk);
        }
        // Проверка адреса настоящая, загрузка подменяется в тестах: сеть не используется.
        @Bean PublicationImages images() {
            return spy(new PublicationImages("https://new.ttg.club", "https://dev.ttg.club", mock(java.net.http.HttpClient.class)));
        }
        @Bean PublicationTestSender testSender(PublicationService service, PublicationSender sender, PublicationImages images) {
            return new PublicationTestSender(service, sender, images);
        }
    }
    @Autowired DataSource source;
    @Autowired JdbcTemplate jdbc;
    @Autowired PublicationService service;
    @Autowired GameDigest digest;
    @Autowired ObjectMapper mapper;
    @Autowired PublicationSecrets secrets;
    @Autowired DiscordWebhookClient client;
    @Autowired PublicationTestSender testSender;
    @Autowired TelegramBotClient telegram;
    @Autowired VkWallClient vk;
    @Autowired PublicationImages images;
    private static final List<Slot> GLOBAL = List.of(new Slot(1, "18:00"), new Slot(4, "20:00"));
    private static final String WEBHOOK = "https://discord.com/api/webhooks/123456789012345678/" + "a".repeat(60);

    /** Выполняет настоящую миграцию на чистой тестовой базе. */
    @BeforeEach void prepare() {
        reset(client, telegram, vk, images);
        when(telegram.configured()).thenReturn(true);
        when(vk.configured()).thenReturn(true);
        when(vk.defaultGroupId()).thenReturn("");
        jdbc.execute("drop all objects");
        new ResourceDatabasePopulator(new ClassPathResource("db/changelog/changes/057-discord-publications.sql")).execute(source);
        new ResourceDatabasePopulator(new ClassPathResource("db/changelog/changes/058-telegram-publications.sql")).execute(source);
        new ResourceDatabasePopulator(new ClassPathResource("db/changelog/changes/059-vk-publications.sql")).execute(source);
        new ResourceDatabasePopulator(new ClassPathResource("db/changelog/changes/060-publication-images.sql")).execute(source);
        jdbc.execute("create table game_systems (code varchar primary key, name varchar)");
        jdbc.execute("create table games (id uuid primary key, title varchar, game_system varchar, custom_system varchar, max_players integer, list_position_at timestamp with time zone, status varchar, visibility varchar, deleted_at timestamp with time zone, recruitment_closed boolean)");
        jdbc.execute("create table game_registrations (id uuid primary key, game_id uuid, status varchar)");
        jdbc.execute("alter table games add column description text default '' not null");
        jdbc.execute("alter table games add column custom_genre varchar(100)");
        jdbc.execute("create table genres (id uuid primary key, name varchar(100))");
        jdbc.execute("create table game_genres (game_id uuid, genre_id uuid, primary key (game_id, genre_id))");
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
        service.saveChannel(channel.id(), new ChannelInput("Новое имя", false, "", null, channel.revision(), null, Platform.DISCORD, null, null));
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
        assertThat(secrets.decrypt(Platform.DISCORD, storedSecret)).isEqualTo(WEBHOOK);
        assertThatThrownBy(() -> addChannel("Повтор", null, WEBHOOK.replace("/api/", "/api/v10/"))).isInstanceOf(ResponseStatusException.class);
        service.saveChannel(channel.id(), new ChannelInput("Изменён", true, "", null, 0, null, Platform.DISCORD, null, null));
        assertThatThrownBy(() -> service.saveChannel(channel.id(), new ChannelInput("Устарел", true, "", null, 0, null, Platform.DISCORD, null, null))).isInstanceOf(ResponseStatusException.class);
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
        assertThat(selected).hasSize(8);
        assertThat(selected.subList(0, 4)).extracting(GameEntry::id).contains(rare, customFirst, customSecond).doesNotContain(sameCustom);
        assertThat(selected).extracting(GameEntry::id).doesNotContain(privateGame, closed, draft, stopped, deleted, full);
        assertThat(selected.stream().filter(game -> game.id().equals(rare)).findFirst().orElseThrow().takenSeats()).isEqualTo(1);
        assertThat(selected).allSatisfy(game -> assertThat(game.url()).isEqualTo("https://new.ttg.club/games/" + game.id()));
    }

    /** Жанры не умножают заявки; описание из базы не попадает ни в предпросмотр, ни в сообщение. */
    @Test void genresReachPayloadWithoutDescriptionOrChangingSeats() {
        UUID gameId = game("DND", null, Instant.now(), "OPEN", "PUBLIC", false);
        UUID fantasy = UUID.randomUUID();
        UUID detective = UUID.randomUUID();
        jdbc.update("insert into genres values (?, 'Фэнтези'), (?, 'Детектив')", fantasy, detective);
        jdbc.update("insert into game_genres values (?, ?), (?, ?)", gameId, fantasy, gameId, detective);
        jdbc.update("insert into game_registrations values (?, ?, 'APPROVED'), (?, ?, 'REJECTED')", UUID.randomUUID(), gameId, UUID.randomUUID(), gameId);
        jdbc.update("update games set custom_genre = ?, description = ? where id = ?", "Городские тайны", """
                [{"type":"h","attrs":{"level":2},"content":["Загадка города"]},
                 {"type":"link","attrs":{"href":"https://hidden.example"},"content":["Найдите пропавшего мага."]}]
                """, gameId);
        List<GameEntry> preview = digest.preview();
        assertThat(preview).singleElement().satisfies(game -> {
            assertThat(game.genreSummary()).isEqualTo("Детектив, Фэнтези, Городские тайны");
            assertThat(game.description()).isEmpty();
            assertThat(game.takenSeats()).isEqualTo(1);
        });
        var payload = mapper.valueToTree(digest.messages(preview, Platform.DISCORD).getFirst().payload());
        assertThat(payload.has("embeds")).isFalse();
        assertThat(payload.path("content").asString())
                .startsWith("Ищете компанию для приключений? 🎲\n\n")
                .contains("игры с открытым набором на new.ttg.club.")
                .contains("Занято 1/4 · Свободно 3", "Жанры: " + preview.getFirst().genreSummary(),
                        "\n[Подробнее](https://new.ttg.club/games/" + gameId + ")")
                .doesNotContain("Загадка города", "Найдите пропавшего мага", "hidden.example", "attrs", "content", "\n> ");
    }

    /** Небольшой каталог публикуется без искусственного заполнения до восьми. */
    @Test void emptyAndSmallCatalogue() {
        assertThat(digest.preview()).isEmpty();
        game("DND", null, Instant.now(), "OPEN", "PUBLIC", false);
        List<GameEntry> preview = digest.preview();
        assertThat(preview).singleElement().satisfies(game -> {
            assertThat(game.genreSummary()).isEmpty();
            assertThat(game.description()).isEmpty();
        });
        assertThat(mapper.writeValueAsString(digest.messages(preview, Platform.DISCORD))).doesNotContain("Жанры:", "\\n> ");
    }

    /** Полный выпуск уходит одним запросом; даже длинные описания игр не публикуются. */
    @Test void fullDigestIsPublishedOnceWithEightGames() {
        service.saveSettings(new SettingsInput(true, GLOBAL, 0));
        Channel channel = addChannel("Канал", null, WEBHOOK);
        Instant now = Instant.now();
        for (int index = 0; index < 8; index++) {
            UUID gameId = game("DND", null, now.minusSeconds(index), "OPEN", "PUBLIC", false);
            jdbc.update("update games set description = ? where id = ?", "Герои " + "расследуют ".repeat(45) + "тайну.", gameId);
        }
        due(channel.id(), now.minusSeconds(1));
        Delivery delivery = service.claim(now);
        List<GameEntry> preview = digest.preview();
        assertThat(preview).hasSize(8);
        assertThat(preview).allSatisfy(game -> assertThat(game.description()).isEmpty());
        when(client.send(eq(WEBHOOK), anyMap(), isNull())).thenReturn(new Outcome("SENT", "Опубликовано", "111111111111111111", null));
        new PublicationScheduler(service, digest, new PublicationSender(secrets, client, mock(TelegramBotClient.class), mock(VkWallClient.class)), images).publish(delivery);
        assertThat(service.history()).singleElement().satisfies(run -> {
            assertThat(run.status()).isEqualTo("SENT");
            assertThat(run.detail()).isEqualTo("Опубликовано сообщений: 1");
            assertThat(run.messageId()).isEqualTo("111111111111111111");
            assertThat(run.gameCount()).isEqualTo(8);
        });
        verify(client).send(eq(WEBHOOK), argThat(payload -> {
            String content = payload.get("content").toString();
            assertThat(content.length()).isLessThanOrEqualTo(2000);
            assertThat(content).doesNotContain("Герои", "расследуют", "\n> ", "Подробнее на сайте");
            assertThat(content.substring(content.lastIndexOf("\n\n")))
                    .contains("[полный список игр на сайте](https://new.ttg.club/games)")
                    .endsWith("Будем рады каждому!");
            for (GameEntry game : preview) {
                assertThat(content).containsOnlyOnce(game.url());
            }
            return true;
        }), isNull());
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
        Delivery finalAttempt = new Delivery(claimed.runId(), claimed.channelId(), claimed.channelRevision(), claimed.secret(), claimed.scheduledAt(), 3, Platform.DISCORD, null);
        service.finish(finalAttempt, new Outcome("RETRY", "Лимит", null, now.plusSeconds(60)), 2, now);
        assertThat(service.history().getFirst().status()).isEqualTo("FAILED");
        assertThat(service.claim(now.plusSeconds(59))).isNull();
        assertThat(service.claim(now.plusSeconds(61)).channelId()).isEqualTo(second.id());
    }

    /** Сохранённый выключенный канал проверяется до включения расписания; HTTP не удерживает транзакцию. */
    @Test void testSendsSavedSecretWithoutEnablingOrChangingSchedule() {
        Channel channel = service.saveChannel(null, new ChannelInput("Тестовый канал", false, WEBHOOK, null, 0, null, Platform.DISCORD, null, null)).channels().getFirst();
        Overview before = service.overview();
        when(client.send(eq(WEBHOOK), anyMap(), isNull())).thenAnswer(invocation -> {
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
        verify(client, times(1)).send(eq(WEBHOOK), anyMap(), isNull());
    }

    /** Пробная отправка сохраняет дату плановой публикации, а устаревшие и удалённые каналы не отправляются. */
    @Test void testPreservesNextRunAndRejectsChangedChannels() {
        service.saveSettings(new SettingsInput(true, GLOBAL, 0));
        Channel channel = addChannel("Канал", null, WEBHOOK);
        Overview before = service.overview();
        Delivery delivery = service.claimTest(channel.id(), channel.revision(), Instant.now());
        assertThat(service.overview()).isEqualTo(before);
        service.saveChannel(channel.id(), new ChannelInput("Новое имя", true, "", null, channel.revision(), null, Platform.DISCORD, null, null));
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
        when(client.send(eq(WEBHOOK), anyMap(), isNull())).thenReturn(new Outcome("RETRY", "Лимит", null, retryAt));
        assertThat(testSender.send(channel.id(), channel.revision()).status()).isEqualTo("FAILED");
        assertThat(service.overview().settings().pausedUntil()).isEqualTo(retryAt);
        assertThat(service.history().getFirst().status()).isEqualTo("FAILED");
        due(channel.id(), retryAt.plusSeconds(3600));
        assertThat(service.claim(retryAt.plusSeconds(1))).isNull();
        Channel other = addChannel("Другой", null, WEBHOOK.replace("aaaa", "bbbb"));
        assertThatThrownBy(() -> testSender.send(other.id(), other.revision())).isInstanceOf(ResponseStatusException.class);
        verify(client, times(1)).send(eq(WEBHOOK), anyMap(), isNull());
    }

    /** Ошибки и неоднозначная доставка видны в журнале и не превращаются в успех. */
    @Test void testErrorsStaySafeAndDoNotRetry() {
        Channel first = addChannel("Первый", null, WEBHOOK);
        when(client.send(eq(WEBHOOK), anyMap(), isNull())).thenReturn(new Outcome("FAILED", "Discord отклонил отправку (HTTP 404)", null, null));
        assertThat(testSender.send(first.id(), first.revision()).status()).isEqualTo("FAILED");
        String otherWebhook = WEBHOOK.replace("aaaa", "bbbb");
        Channel second = addChannel("Второй", null, otherWebhook);
        when(client.send(eq(otherWebhook), anyMap(), isNull())).thenThrow(new IllegalStateException(otherWebhook));
        TestResult uncertain = testSender.send(second.id(), second.revision());
        assertThat(uncertain.status()).isEqualTo("UNKNOWN");
        assertThat(uncertain.detail()).doesNotContain(otherWebhook);
        assertThat(service.history()).extracting(Run::status).containsExactlyInAnyOrder("FAILED", "UNKNOWN");
        assertThat(service.claim(Instant.now().plusSeconds(121))).isNull();
        verify(client, times(2)).send(anyString(), anyMap(), isNull());
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

    /** Telegram хранится зашифрованным, использует общий график и не отдаёт ID обратно в API. */
    @Test void telegramStorageAndSecretReplacement() {
        service.saveSettings(new SettingsInput(true, GLOBAL, 0));
        String chatId = "-1001234567890";
        Channel channel = service.saveChannel(null, new ChannelInput("Telegram", true, "", null, 0, chatId, Platform.TELEGRAM, null, null)).channels().getFirst();
        assertThat(channel.platform()).isEqualTo(Platform.TELEGRAM);
        assertThat(channel.nextRunAt()).isNotNull();
        String encrypted = jdbc.queryForObject("select webhook_secret from discord_publication_channels where id = ?", String.class, channel.id());
        String fingerprint = jdbc.queryForObject("select webhook_fingerprint from discord_publication_channels where id = ?", String.class, channel.id());
        assertThat(encrypted).doesNotContain(chatId);
        assertThat(secrets.decrypt(Platform.TELEGRAM, encrypted)).isEqualTo(chatId);
        assertThat(mapper.writeValueAsString(service.overview())).doesNotContain(chatId, encrypted, fingerprint, "telegramChatId", "webhookUrl");
        assertThatThrownBy(() -> service.saveChannel(null, new ChannelInput("Повтор", true, "", null, 0, chatId, Platform.TELEGRAM, null, null)))
                .isInstanceOf(ResponseStatusException.class);
        service.saveChannel(channel.id(), new ChannelInput("Переименован", true, "", null, 0, "", Platform.TELEGRAM, null, null));
        assertThat(jdbc.queryForObject("select webhook_secret from discord_publication_channels where id = ?", String.class, channel.id())).isEqualTo(encrypted);
        assertThatThrownBy(() -> service.saveChannel(channel.id(), new ChannelInput("Смена платформы", true, WEBHOOK, null, 1, null, Platform.DISCORD, null, null)))
                .isInstanceOf(ResponseStatusException.class);
        service.saveChannel(channel.id(), new ChannelInput("Новый ID", true, "", null, 1, "-1001234567891", Platform.TELEGRAM, null, null));
        assertThat(secrets.decrypt(Platform.TELEGRAM, jdbc.queryForObject("select webhook_secret from discord_publication_channels where id = ?", String.class, channel.id())))
                .isEqualTo("-1001234567891");
    }

    /** Недоступный бот не мешает сохранять ID и публиковать в Discord. */
    @Test void missingTelegramTokenDoesNotBlockDiscord() {
        when(telegram.configured()).thenReturn(false);
        service.saveSettings(new SettingsInput(true, GLOBAL, 0));
        Channel telegramChannel = service.saveChannel(null, new ChannelInput("Telegram", true, "", null, 0, "-1001234567890", Platform.TELEGRAM, null, null)).channels().getFirst();
        Channel discordChannel = addChannel("Discord", null, WEBHOOK);
        Instant now = Instant.now();
        due(telegramChannel.id(), now.minusSeconds(20));
        due(discordChannel.id(), now.minusSeconds(10));
        assertThat(service.overview().telegramConfigured()).isFalse();
        assertThatThrownBy(() -> service.claimTest(telegramChannel.id(), 0, now)).isInstanceOf(ResponseStatusException.class);
        assertThat(service.claim(now).platform()).isEqualTo(Platform.DISCORD);
    }

    /** Telegram получает одно сообщение с той же подборкой, а лимит не останавливает Discord. */
    @Test void telegramDeliveryAndIndependentRateLimit() {
        service.saveSettings(new SettingsInput(true, GLOBAL, 0));
        Channel telegramChannel = service.saveChannel(null, new ChannelInput("Telegram", true, "", null, 0, "-1001234567890", Platform.TELEGRAM, null, null)).channels().getFirst();
        Channel discordChannel = addChannel("Discord", null, WEBHOOK);
        Instant now = Instant.now();
        game("DND", null, now, "OPEN", "PUBLIC", false);
        due(telegramChannel.id(), now.minusSeconds(20));
        due(discordChannel.id(), now.minusSeconds(10));
        Delivery delivery = service.claim(now);
        assertThat(delivery.platform()).isEqualTo(Platform.TELEGRAM);
        when(telegram.send(eq("-1001234567890"), anyMap(), isNull())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            Map<String, Object> payload = invocation.getArgument(1);
            assertThat(payload).containsEntry("parse_mode", "HTML").containsEntry("link_preview_options", Map.of("is_disabled", true));
            assertThat(payload.get("text").toString()).contains("https://new.ttg.club/games/", ">Подробнее</a>",
                    "<a href=\"https://new.ttg.club/games\">полный список игр на сайте</a>")
                    .doesNotContain("Подробнее на сайте");
            return new Outcome("RETRY", "Лимит", null, now.plusSeconds(60));
        });
        new PublicationScheduler(service, digest, new PublicationSender(secrets, client, telegram, vk), images).publish(delivery);
        assertThat(service.claim(now.plusSeconds(1)).channelId()).isEqualTo(discordChannel.id());
        assertThat(service.claim(now.plusSeconds(59))).isNull();
        Delivery retry = service.claim(now.plusSeconds(61));
        assertThat(retry.channelId()).isEqualTo(telegramChannel.id());
        when(telegram.send(anyString(), anyMap(), isNull())).thenReturn(new Outcome("SENT", "Опубликовано", "42", null));
        new PublicationScheduler(service, digest, new PublicationSender(secrets, client, telegram, vk), images).publish(retry);
        assertThat(service.history()).filteredOn(run -> run.platform() == Platform.TELEGRAM).singleElement()
                .satisfies(run -> { assertThat(run.status()).isEqualTo("SENT"); assertThat(run.messageId()).isEqualTo("42"); });
        verifyNoInteractions(client);
    }

    /** Картинка сохраняется полным адресом сайта: без поля остаётся прежней, пустое значение убирает, чужой адрес отклоняется. */
    @Test void channelImageIsStoredAndReachesDeliveries() {
        service.saveSettings(new SettingsInput(true, GLOBAL, 0));
        String image = "https://new.ttg.club/s3/game-publications/admin/1758560000000-cover.webp";
        Channel channel = service.saveChannel(null, new ChannelInput("Канал", true, WEBHOOK, null, 0, null, Platform.DISCORD, null,
                " /s3/game-publications/admin/1758560000000-cover.webp ")).channels().getFirst();
        assertThat(channel.imageUrl()).isEqualTo(image);
        Channel renamed = service.saveChannel(channel.id(), new ChannelInput("Новое имя", true, "", null, channel.revision(), null, Platform.DISCORD, null, null))
                .channels().getFirst();
        assertThat(renamed.imageUrl()).isEqualTo(image);
        due(channel.id(), Instant.now().minusSeconds(10));
        assertThat(service.claim(Instant.now()).imageUrl()).isEqualTo(image);
        Channel fromDev = service.saveChannel(channel.id(), new ChannelInput("Канал", true, "", null, renamed.revision(), null,
                Platform.DISCORD, null, "https://dev.ttg.club/s3/game-publications/admin/1-cover.webp")).channels().getFirst();
        assertThat(fromDev.imageUrl()).isEqualTo("https://dev.ttg.club/s3/game-publications/admin/1-cover.webp");
        for (String invalid : List.of("https://evil.example/cover.png", "/s3/a/../b.png")) {
            assertThatThrownBy(() -> service.saveChannel(channel.id(), new ChannelInput("Канал", true, "", null, fromDev.revision(), null, Platform.DISCORD, null, invalid)))
                    .isInstanceOf(ResponseStatusException.class);
        }
        Channel cleared = service.saveChannel(channel.id(), new ChannelInput("Канал", true, "", null, fromDev.revision(), null, Platform.DISCORD, null, ""))
                .channels().getFirst();
        assertThat(cleared.imageUrl()).isNull();
        assertThat(jdbc.queryForObject("select image_url from discord_publication_channels", String.class)).isNull();
    }

    /** Тест отправляет картинку канала; недоступная картинка сообщается без отправки текста вместо неё. */
    @Test void testMessageCarriesChannelImage() throws Exception {
        String image = "https://new.ttg.club/s3/game-publications/admin/1758560000000-cover.webp";
        ImageFile file = new ImageFile(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}, "image/jpeg");
        Channel telegramChannel = service.saveChannel(null, new ChannelInput("Telegram", false, "", null, 0, "-1001234567890", Platform.TELEGRAM, null, image)).channels().getFirst();
        doReturn(file).when(images).load(image);
        when(telegram.send(eq("-1001234567890"), anyMap(), eq(file))).thenReturn(new Outcome("SENT", "Опубликовано", "42", null));
        assertThat(testSender.send(telegramChannel.id(), telegramChannel.revision()).status()).isEqualTo("SENT");
        verify(telegram).send(eq("-1001234567890"), argThat(payload -> payload.keySet().equals(Set.of("caption"))
                && payload.get("caption").toString().contains("Тестовое сообщение")), eq(file));
        Channel discordChannel = service.saveChannel(null, new ChannelInput("Discord", false, WEBHOOK, null, 0, null, Platform.DISCORD, null, image))
                .channels().stream().filter(channel -> channel.platform() == Platform.DISCORD).findFirst().orElseThrow();
        doThrow(new PublicationImages.Unavailable("сайт не отдал картинку (HTTP 404)")).when(images).load(image);
        TestResult missing = testSender.send(discordChannel.id(), discordChannel.revision());
        assertThat(missing.status()).isEqualTo("FAILED");
        assertThat(missing.detail()).isEqualTo("Картинка канала недоступна: сайт не отдал картинку (HTTP 404); выберите её заново");
        verifyNoInteractions(client);
        assertThat(service.history()).extracting(Run::detail).contains("Тест: " + missing.detail());
    }

    /** Тест бота не включает график и не отправляет подборку вместо проверки. */
    @Test void telegramTestUsesSavedIdAndKeepsSchedule() {
        Channel channel = service.saveChannel(null, new ChannelInput("Telegram", false, "", null, 0, "-1001234567890", Platform.TELEGRAM, null, null)).channels().getFirst();
        Overview before = service.overview();
        when(telegram.send(eq("-1001234567890"), anyMap(), isNull())).thenReturn(new Outcome("SENT", "Опубликовано", "42", null));
        assertThat(testSender.send(channel.id(), channel.revision()).status()).isEqualTo("SENT");
        assertThat(service.overview()).isEqualTo(before);
        verify(telegram).send(eq("-1001234567890"), argThat(payload -> payload.get("text").toString().contains("Тестовое сообщение")), isNull());
        verifyNoInteractions(client);
        assertThat(service.history()).singleElement().satisfies(run -> { assertThat(run.platform()).isEqualTo(Platform.TELEGRAM); assertThat(run.gameCount()).isZero(); });
    }

    /** Обновление сохраняет существующие каналы, зашифрованные вебхуки и журнал Discord. */
    @Test void migrationPreservesExistingDiscordData() {
        jdbc.execute("drop all objects");
        new ResourceDatabasePopulator(new ClassPathResource("db/changelog/changes/057-discord-publications.sql")).execute(source);
        UUID channelId = UUID.randomUUID();
        String encrypted = secrets.encrypt(Platform.DISCORD, WEBHOOK);
        Timestamp now = Timestamp.from(Instant.now().minusSeconds(61));
        jdbc.update("insert into discord_publication_channels (id, name, webhook_secret, webhook_fingerprint, enabled) values (?, 'Прежний канал', ?, ?, true)",
                channelId, encrypted, secrets.fingerprint(Platform.DISCORD, WEBHOOK));
        jdbc.update("insert into discord_publication_runs (id, channel_id, channel_name, channel_revision, scheduled_at, started_at, status, message_id) values (?, ?, 'Прежний канал', 0, ?, ?, 'SENT', '123')",
                UUID.randomUUID(), channelId, now, now);
        new ResourceDatabasePopulator(new ClassPathResource("db/changelog/changes/058-telegram-publications.sql")).execute(source);
        new ResourceDatabasePopulator(new ClassPathResource("db/changelog/changes/059-vk-publications.sql")).execute(source);
        new ResourceDatabasePopulator(new ClassPathResource("db/changelog/changes/060-publication-images.sql")).execute(source);
        assertThat(service.overview().channels()).singleElement().satisfies(channel -> {
            assertThat(channel.id()).isEqualTo(channelId);
            assertThat(channel.platform()).isEqualTo(Platform.DISCORD);
        });
        assertThat(service.history()).singleElement().satisfies(run -> {
            assertThat(run.platform()).isEqualTo(Platform.DISCORD);
            assertThat(run.messageId()).isEqualTo("123");
        });
        String migrated = jdbc.queryForObject("select webhook_secret from discord_publication_channels where id = ?", String.class, channelId);
        assertThat(migrated).isEqualTo(encrypted);
        assertThat(secrets.decrypt(Platform.DISCORD, migrated)).isEqualTo(WEBHOOK);
        when(client.send(eq(WEBHOOK), anyMap(), isNull())).thenReturn(new Outcome("SENT", "Опубликовано", "124", null));
        assertThat(testSender.send(channelId, 0).status()).isEqualTo("SENT");
    }

    /** Ограничение Discord не блокирует Telegram, включая ручную проверку. */
    @Test void discordPauseDoesNotBlockTelegram() {
        Channel discordChannel = addChannel("Discord", null, WEBHOOK);
        Channel telegramChannel = service.saveChannel(null, new ChannelInput("Telegram", false, "", null, 0, "-1001234567890", Platform.TELEGRAM, null, null))
                .channels().stream().filter(channel -> channel.platform() == Platform.TELEGRAM).findFirst().orElseThrow();
        when(client.send(eq(WEBHOOK), anyMap(), isNull())).thenReturn(new Outcome("RETRY", "Лимит", null, Instant.now().plusSeconds(60)));
        assertThat(testSender.send(discordChannel.id(), 0).status()).isEqualTo("FAILED");
        when(telegram.send(eq("-1001234567890"), anyMap(), isNull())).thenReturn(new Outcome("SENT", "Опубликовано", "42", null));
        assertThat(testSender.send(telegramChannel.id(), 0).status()).isEqualTo("SENT");
    }

    /** ID сообщества VK хранится зашифрованным, подборка уходит одной записью, тест не меняет график. */
    @Test void vkStorageDeliveryAndTest() {
        service.saveSettings(new SettingsInput(true, GLOBAL, 0));
        Channel channel = service.saveChannel(null, new ChannelInput("ВКонтакте", true, "", null, 0, "", Platform.VK, "-212345678", null)).channels().getFirst();
        assertThat(channel.platform()).isEqualTo(Platform.VK);
        String encrypted = jdbc.queryForObject("select webhook_secret from discord_publication_channels where id = ?", String.class, channel.id());
        assertThat(encrypted).startsWith("vk-v1:").doesNotContain("212345678");
        assertThat(secrets.decrypt(Platform.VK, encrypted)).isEqualTo("212345678");
        assertThat(mapper.writeValueAsString(service.overview())).doesNotContain("212345678", encrypted, "vkGroupId");
        assertThat(service.overview().vkConfigured()).isTrue();
        assertThatThrownBy(() -> service.saveChannel(null, new ChannelInput("Повтор", true, "", null, 0, "", Platform.VK, "212345678", null)))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.saveChannel(null, new ChannelInput("Чужой адрес", true, "", null, 0, "-1001234567890", Platform.VK, "212345679", null)))
                .isInstanceOf(ResponseStatusException.class);
        Instant now = Instant.now();
        game("DND", null, now, "OPEN", "PUBLIC", false);
        due(channel.id(), now.minusSeconds(10));
        Delivery delivery = service.claim(now);
        assertThat(delivery.platform()).isEqualTo(Platform.VK);
        when(vk.send(eq("212345678"), anyMap(), isNull())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            Map<String, Object> payload = invocation.getArgument(1);
            assertThat(payload).containsOnlyKeys("message");
            assertThat(payload.get("message").toString()).contains("\nПодробнее: https://new.ttg.club/games/",
                    "полный список игр на сайте (https://new.ttg.club/games)").doesNotContain("<a", "[Подробнее", "](");
            return new Outcome("SENT", "Опубликовано", "4242", null);
        });
        new PublicationScheduler(service, digest, new PublicationSender(secrets, client, telegram, vk), images).publish(delivery);
        assertThat(service.history()).singleElement().satisfies(run -> {
            assertThat(run.platform()).isEqualTo(Platform.VK);
            assertThat(run.status()).isEqualTo("SENT");
            assertThat(run.messageId()).isEqualTo("4242");
            assertThat(run.gameCount()).isEqualTo(1);
        });
        verify(telegram, never()).send(anyString(), anyMap(), isNull());
        verifyNoInteractions(client);
    }

    /** Тест ВКонтакте публикует проверочную запись по сохранённому ID и не включает график. */
    @Test void vkTestUsesSavedIdAndKeepsSchedule() {
        Channel channel = service.saveChannel(null, new ChannelInput("ВКонтакте", false, "", null, 0, "", Platform.VK, "212345678", null)).channels().getFirst();
        Overview before = service.overview();
        when(vk.send(eq("212345678"), anyMap(), isNull())).thenReturn(new Outcome("SENT", "Опубликовано", "4242", null));
        assertThat(testSender.send(channel.id(), channel.revision()).status()).isEqualTo("SENT");
        assertThat(service.overview()).isEqualTo(before);
        verify(vk).send(eq("212345678"), argThat(payload -> payload.get("message").toString().contains("Тестовое сообщение")), isNull());
        verifyNoInteractions(client);
        assertThat(service.history()).singleElement().satisfies(run -> { assertThat(run.platform()).isEqualTo(Platform.VK); assertThat(run.gameCount()).isZero(); });
    }

    /** Канал без своего ID публикует в сообщество из VK_GROUP_ID; без него ID обязателен. */
    @Test void vkChannelFallsBackToServerGroup() {
        assertThat(service.overview().vkGroupConfigured()).isFalse();
        assertThatThrownBy(() -> service.saveChannel(null, new ChannelInput("ВКонтакте", true, "", null, 0, "", Platform.VK, "", null)))
                .isInstanceOf(ResponseStatusException.class);
        when(vk.defaultGroupId()).thenReturn("-212345678");
        assertThat(service.overview().vkGroupConfigured()).isTrue();
        Channel channel = service.saveChannel(null, new ChannelInput("ВКонтакте", true, "", null, 0, "", Platform.VK, "", null)).channels().getFirst();
        String encrypted = jdbc.queryForObject("select webhook_secret from discord_publication_channels where id = ?", String.class, channel.id());
        assertThat(secrets.decrypt(Platform.VK, encrypted)).isEqualTo("212345678");
        assertThatThrownBy(() -> service.saveChannel(null, new ChannelInput("Повтор", true, "", null, 0, "", Platform.VK, "", null)))
                .isInstanceOf(ResponseStatusException.class);
        // Сохранённый ID не меняется при правке канала и при смене переменной окружения.
        when(vk.defaultGroupId()).thenReturn("312345678");
        service.saveChannel(channel.id(), new ChannelInput("Переименован", true, "", null, 0, "", Platform.VK, "", null));
        assertThat(jdbc.queryForObject("select webhook_secret from discord_publication_channels where id = ?", String.class, channel.id())).isEqualTo(encrypted);
        assertThatThrownBy(() -> service.saveChannel(null, new ChannelInput("Discord", true, "", null, 0, "", Platform.DISCORD, "", null)))
                .isInstanceOf(ResponseStatusException.class);
    }

    /** Без ключа сообщества ID сохраняется, а остальные платформы продолжают работу. */
    @Test void missingVkTokenDoesNotBlockOtherPlatforms() {
        when(vk.configured()).thenReturn(false);
        service.saveSettings(new SettingsInput(true, GLOBAL, 0));
        Channel vkChannel = service.saveChannel(null, new ChannelInput("ВКонтакте", true, "", null, 0, "", Platform.VK, "212345678", null)).channels().getFirst();
        Channel discordChannel = addChannel("Discord", null, WEBHOOK);
        Instant now = Instant.now();
        due(vkChannel.id(), now.minusSeconds(20));
        due(discordChannel.id(), now.minusSeconds(10));
        assertThat(service.overview().vkConfigured()).isFalse();
        assertThatThrownBy(() -> service.claimTest(vkChannel.id(), 0, now)).isInstanceOf(ResponseStatusException.class);
        assertThat(service.claim(now).platform()).isEqualTo(Platform.DISCORD);
    }

    /** Ограничение VK ставит паузу только своей платформе и допускает один повтор выпуска. */
    @Test void vkRateLimitIsIndependent() {
        service.saveSettings(new SettingsInput(true, GLOBAL, 0));
        Channel vkChannel = service.saveChannel(null, new ChannelInput("ВКонтакте", true, "", null, 0, "", Platform.VK, "212345678", null)).channels().getFirst();
        Channel discordChannel = addChannel("Discord", null, WEBHOOK);
        Instant now = Instant.now();
        game("DND", null, now, "OPEN", "PUBLIC", false);
        due(vkChannel.id(), now.minusSeconds(20));
        due(discordChannel.id(), now.minusSeconds(10));
        Delivery delivery = service.claim(now);
        assertThat(delivery.platform()).isEqualTo(Platform.VK);
        when(vk.send(anyString(), anyMap(), isNull())).thenReturn(new Outcome("RETRY", "Лимит", null, now.plusSeconds(5)));
        new PublicationScheduler(service, digest, new PublicationSender(secrets, client, telegram, vk), images).publish(delivery);
        assertThat(service.claim(now.plusSeconds(1)).channelId()).isEqualTo(discordChannel.id());
        assertThat(service.claim(now.plusSeconds(4))).isNull();
        assertThat(service.claim(now.plusSeconds(6)).runId()).isEqualTo(delivery.runId());
    }

    /** Миграция 059 сохраняет каналы и журнал Discord и Telegram и допускает только известные платформы. */
    @Test void vkMigrationPreservesExistingChannels() {
        jdbc.execute("drop all objects");
        new ResourceDatabasePopulator(new ClassPathResource("db/changelog/changes/057-discord-publications.sql")).execute(source);
        new ResourceDatabasePopulator(new ClassPathResource("db/changelog/changes/058-telegram-publications.sql")).execute(source);
        UUID discordId = UUID.randomUUID();
        UUID telegramId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now().minusSeconds(61));
        jdbc.update("insert into discord_publication_channels (id, name, webhook_secret, webhook_fingerprint, enabled, platform) values (?, 'Discord', ?, ?, true, 'discord'), (?, 'Telegram', ?, ?, true, 'telegram')",
                discordId, secrets.encrypt(Platform.DISCORD, WEBHOOK), secrets.fingerprint(Platform.DISCORD, WEBHOOK),
                telegramId, secrets.encrypt(Platform.TELEGRAM, "-1001234567890"), secrets.fingerprint(Platform.TELEGRAM, "-1001234567890"));
        jdbc.update("insert into discord_publication_runs (id, channel_id, channel_name, channel_revision, scheduled_at, started_at, status, message_id, platform) values (?, ?, 'Telegram', 0, ?, ?, 'SENT', '42', 'telegram')",
                UUID.randomUUID(), telegramId, now, now);
        new ResourceDatabasePopulator(new ClassPathResource("db/changelog/changes/059-vk-publications.sql")).execute(source);
        new ResourceDatabasePopulator(new ClassPathResource("db/changelog/changes/060-publication-images.sql")).execute(source);
        assertThat(service.overview().channels()).extracting(Channel::id, Channel::platform)
                .containsExactlyInAnyOrder(tuple(discordId, Platform.DISCORD), tuple(telegramId, Platform.TELEGRAM));
        assertThat(service.history()).singleElement().satisfies(run -> {
            assertThat(run.platform()).isEqualTo(Platform.TELEGRAM);
            assertThat(run.messageId()).isEqualTo("42");
        });
        assertThatThrownBy(() -> jdbc.update("update discord_publication_channels set platform = 'slack' where id = ?", discordId))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(service.saveChannel(null, new ChannelInput("ВКонтакте", true, "", null, 0, "", Platform.VK, "212345678", null)).channels())
                .extracting(Channel::platform).contains(Platform.VK);
    }

    private Channel addChannel(String name, List<Slot> schedule, String webhook) {
        return service.saveChannel(null, new ChannelInput(name, true, webhook, schedule, 0, null, Platform.DISCORD, null, null)).channels().stream()
                .filter(channel -> channel.name().equals(name)).findFirst().orElseThrow();
    }
    private Channel find(Overview overview, UUID channelId) { return overview.channels().stream().filter(channel -> channel.id().equals(channelId)).findFirst().orElseThrow(); }
    private void due(UUID channelId, Instant instant) { jdbc.update("update discord_publication_channels set next_run_at = ? where id = ?", Timestamp.from(instant), channelId); }
    private UUID game(String system, String customSystem, Instant position, String status, String visibility, boolean stopped) {
        UUID gameId = UUID.randomUUID();
        jdbc.update("insert into games (id, title, game_system, custom_system, max_players, list_position_at, status, visibility, recruitment_closed) values (?, ?, ?, ?, 4, ?, ?, ?, ?)", gameId, "Игра " + gameId, system, customSystem, Timestamp.from(position), status, visibility, stopped);
        return gameId;
    }
}
