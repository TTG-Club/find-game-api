package club.ttg.findgame.publications;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URLDecoder;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import static club.ttg.findgame.publications.PublicationModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Проверяет картинку канала: загрузку с сайта, формат для платформ и выпуск без картинки при отказе. */
class PublicationImageTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private static final String PATH = "/s3/game-publications/admin/1758560000000-cover.webp";
    /** 40×20, непрозрачная, сжата sharp так же, как загрузка сайта. */
    private static final byte[] OPAQUE_WEBP = Base64.getDecoder().decode(
            "UklGRkwAAABXRUJQVlA4IEAAAAAwAwCdASooABQAPm02l0ikIyIhJWgAgA2JZwDJEA8Kvu4AAP7vvVeuLmyIo//srH/+lY//0rH8OMGJ/ZrmsAAA");
    /** 8×8, полупрозрачная. */
    private static final byte[] ALPHA_WEBP = Base64.getDecoder().decode(
            "UklGRmAAAABXRUJQVlA4WAoAAAAQAAAABwAABwAAQUxQSAoAAAABB1DAiAhERP8DVlA4IDAAAAAQAgCdASoIAAgAAUAmJaACdLoB+AH4AAPIAP7u/5/+oLfJnnz2//r0QeIN+dkAAAA=");
    /** 420×20: соотношение сторон больше 20, Telegram такое фото не примет. */
    private static final byte[] WIDE_WEBP = Base64.getDecoder().decode(
            "UklGRk4AAABXRUJQVlA4IEIAAADwBQCdASqkARQAPtFosFMoJiSioKgBABoJaW7hdJAAY2upvcReWAa6m9xF5YBrqb3EXlgGupvRAAD+/tBQAAAAAAA=");
    private static final ImageFile IMAGE = new ImageFile(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3}, "image/jpeg");

    /** Сервис скачивает только картинки, загруженные через сайт, а не произвольные адреса. */
    @Test void acceptsOnlySiteUploadPaths() {
        assertThat(PublicationImages.normalize(" " + PATH + " ")).isEqualTo(PATH);
        assertThat(PublicationImages.normalize("/s3/games/user/1-cover")).isEqualTo("/s3/games/user/1-cover");
        for (String invalid : List.of("https://evil.example/cover.png", "//evil.example/s3/a/b.png", "/s3/cover.png",
                "/s3/a/../b.png", "/s3/a/b.png?x=1", "/s3/a//b.png", "/s3/a/b.png#top", "/api/v1/admin/x/y",
                "/s3/a/b c.png", "/s3/a/" + "b".repeat(520) + ".png")) {
            assertThatThrownBy(() -> PublicationImages.normalize(invalid)).isInstanceOf(ResponseStatusException.class);
        }
    }

    /** WebP сайта перекодируется: непрозрачная в JPEG, прозрачная в PNG; JPEG уходит без изменений. */
    @Test void convertsWebpForAllPlatforms() throws Exception {
        ImageFile jpeg = PublicationImages.prepare(OPAQUE_WEBP);
        assertThat(jpeg.contentType()).isEqualTo("image/jpeg");
        assertThat(jpeg.filename()).isEqualTo("games.jpg");
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(jpeg.data()));
        assertThat(decoded.getWidth()).isEqualTo(40);
        assertThat(decoded.getHeight()).isEqualTo(20);
        assertThat((decoded.getRGB(20, 10) >> 16) & 0xFF).isBetween(170, 230);
        ImageFile png = PublicationImages.prepare(ALPHA_WEBP);
        assertThat(png.contentType()).isEqualTo("image/png");
        assertThat(png.filename()).isEqualTo("games.png");
        BufferedImage transparent = ImageIO.read(new ByteArrayInputStream(png.data()));
        assertThat(transparent.getWidth()).isEqualTo(8);
        assertThat(transparent.getRGB(4, 4) >>> 24).isBetween(100, 160);
        assertThat(PublicationImages.prepare(jpeg.data()).data()).isEqualTo(jpeg.data());
        assertThatThrownBy(() -> PublicationImages.prepare(WIDE_WEBP)).isInstanceOf(PublicationImages.Unavailable.class)
                .hasMessageContaining("420×20");
        assertThatThrownBy(() -> PublicationImages.prepare("<html>".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(PublicationImages.Unavailable.class).hasMessageContaining("не является картинкой");
        byte[] broken = Arrays.copyOf(OPAQUE_WEBP, 30);
        assertThatThrownBy(() -> PublicationImages.prepare(broken)).isInstanceOf(PublicationImages.Unavailable.class);
    }

    /** Картинка берётся с сайта подборки; повторная загрузка того же пути приходит из памяти. */
    @Test void downloadsFromSiteOnceAndCaches() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<InputStream> response = mock();
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenAnswer(invocation -> new ByteArrayInputStream(OPAQUE_WEBP));
        when(http.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any())).thenReturn(response);
        PublicationImages images = new PublicationImages("https://new.ttg.club/", http);
        assertThat(images.load(PATH).contentType()).isEqualTo("image/jpeg");
        assertThat(images.load(PATH).contentType()).isEqualTo("image/jpeg");
        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http, times(1)).send(request.capture(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any());
        assertThat(request.getValue().uri().toString()).isEqualTo("https://new.ttg.club" + PATH);
        assertThat(request.getValue().method()).isEqualTo("GET");
        images.shutdown();
        verify(http).shutdown();
    }

    /** Отсутствующий файл, перенаправление, большой файл и сетевой сбой дают безопасную причину без адреса. */
    @Test void unavailableImageHasSafeReason() throws Exception {
        for (int status : List.of(404, 302, 500)) {
            assertThatThrownBy(() -> images(status, new byte[0]).load(PATH)).isInstanceOf(PublicationImages.Unavailable.class)
                    .hasMessageContaining("HTTP " + status).hasMessageNotContaining("new.ttg.club");
        }
        assertThatThrownBy(() -> images(200, new byte[10 * 1024 * 1024 + 1]).load(PATH))
                .isInstanceOf(PublicationImages.Unavailable.class).hasMessageContaining("10 МБ");
        HttpClient http = mock(HttpClient.class);
        when(http.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any()))
                .thenThrow(new java.io.IOException("https://new.ttg.club" + PATH));
        assertThatThrownBy(() -> new PublicationImages("https://new.ttg.club", http).load(PATH))
                .isInstanceOf(PublicationImages.Unavailable.class).hasMessageNotContaining(PATH);
    }

    /** Discord получает текст в payload_json и картинку вложением; упоминания и карточки по-прежнему отключены. */
    @Test void discordSendsImageAsAttachment() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> response = response(200, "{\"id\":\"123456789012345678\"}");
        when(http.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(response);
        DiscordWebhookClient client = new DiscordWebhookClient(mapper, http);
        String webhook = "https://discord.com/api/webhooks/123456789012345678/" + "a".repeat(60);
        Map<String, Object> payload = Map.of("content", "Подборка", "flags", 4, "allowed_mentions", Map.of("parse", List.of()));
        assertThat(client.send(webhook, payload, IMAGE).status()).isEqualTo("SENT");
        HttpRequest request = captured(http).getFirst();
        assertThat(request.uri().toString()).isEqualTo(webhook + "?wait=true");
        Map<String, Part> parts = parts(request);
        assertThat(parts).containsOnlyKeys("payload_json", "files[0]");
        assertThat(parts.get("payload_json").type()).isEqualTo("application/json");
        assertThat(mapper.readTree(parts.get("payload_json").text()).path("content").asText()).isEqualTo("Подборка");
        assertThat(mapper.readTree(parts.get("payload_json").text()).path("flags").asInt()).isEqualTo(4);
        assertThat(parts.get("files[0]").filename()).isEqualTo("games.jpg");
        assertThat(parts.get("files[0]").type()).isEqualTo("image/jpeg");
        assertThat(parts.get("files[0]").data()).isEqualTo(IMAGE.data());
    }

    /** Telegram получает фото методом sendPhoto с подписью; ID чата идёт в теле, а не в адресе. */
    @Test void telegramSendsPhotoWithCaption() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> response = response(200, "{\"ok\":true,\"result\":{\"message_id\":42}}");
        when(http.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(response);
        String token = "123456789:" + "a".repeat(35);
        TelegramBotClient client = new TelegramBotClient(mapper, token, http);
        Outcome outcome = client.send("-1001234567890", Map.of("caption", "Игры &amp; <a href=\"https://new.ttg.club\">сайт</a>", "parse_mode", "HTML"), IMAGE);
        assertThat(outcome.messageId()).isEqualTo("42");
        HttpRequest request = captured(http).getFirst();
        assertThat(request.uri().toString()).isEqualTo("https://api.telegram.org/bot" + token + "/sendPhoto");
        Map<String, Part> parts = parts(request);
        assertThat(parts).containsOnlyKeys("chat_id", "caption", "parse_mode", "photo");
        assertThat(parts.get("chat_id").text()).isEqualTo("-1001234567890");
        assertThat(parts.get("caption").text()).isEqualTo("Игры &amp; <a href=\"https://new.ttg.club\">сайт</a>");
        assertThat(parts.get("photo").data()).isEqualTo(IMAGE.data());
        assertThat(client.interpret(400, "{\"ok\":false,\"error_code\":400}", java.time.Instant.now(), true).detail()).contains("картинк");
    }

    /** Ключ сообщества не получает адрес загрузки на стену: фото идёт через альбом сообщений и прикрепляется к записи. */
    @Test void vkUploadsPhotoBeforeWallPost() throws Exception {
        String token = "vk1.a." + "b".repeat(80);
        HttpClient http = mock(HttpClient.class);
        Map<String, HttpResponse<String>> answers = Map.of(
                "https://api.vk.com/method/photos.getWallUploadServer", response(200, "{\"error\":{\"error_code\":27,\"error_msg\":\"" + token + "\"}}"),
                "https://api.vk.com/method/photos.getMessagesUploadServer", response(200, "{\"response\":{\"upload_url\":\"https://pu.vk.com/c1/upload.php?act=do_add\"}}"),
                "https://pu.vk.com/c1/upload.php?act=do_add", response(200, "{\"server\":777,\"photo\":\"[{\\\"photo\\\":\\\"x\\\"}]\",\"hash\":\"h1\"}"),
                "https://api.vk.com/method/photos.saveMessagesPhoto", response(200, "{\"response\":[{\"id\":457239017,\"owner_id\":-212345678,\"access_key\":\"abc123\"}]}"),
                "https://api.vk.com/method/wall.post", response(200, "{\"response\":{\"post_id\":42}}"));
        when(http.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenAnswer(invocation -> answers.get(invocation.<HttpRequest>getArgument(0).uri().toString()));
        VkWallClient client = new VkWallClient(mapper, token, "", http);
        Outcome outcome = client.send("212345678", Map.of("message", "Подборка"), IMAGE);
        assertThat(outcome.status()).isEqualTo("SENT");
        assertThat(outcome.messageId()).isEqualTo("42");
        List<HttpRequest> requests = captured(http);
        assertThat(requests).extracting(request -> request.uri().toString()).containsExactly(
                "https://api.vk.com/method/photos.getWallUploadServer", "https://api.vk.com/method/photos.getMessagesUploadServer",
                "https://pu.vk.com/c1/upload.php?act=do_add", "https://api.vk.com/method/photos.saveMessagesPhoto",
                "https://api.vk.com/method/wall.post");
        assertThat(form(requests.get(0))).containsEntry("group_id", "212345678");
        Map<String, Part> upload = parts(requests.get(2));
        assertThat(upload).containsOnlyKeys("photo");
        assertThat(upload.get("photo").data()).isEqualTo(IMAGE.data());
        assertThat(new String(bytes(requests.get(2)), StandardCharsets.ISO_8859_1)).doesNotContain(token);
        assertThat(form(requests.get(3))).containsEntry("server", "777").containsEntry("photo", "[{\"photo\":\"x\"}]")
                .containsEntry("hash", "h1").doesNotContainKey("group_id");
        assertThat(form(requests.get(4))).containsEntry("message", "Подборка")
                .containsEntry("attachments", "photo-212345678_457239017_abc123");
    }

    /** Отказ VK от файла ничего не публикует; ограничение частоты откладывает выпуск, а не теряет картинку. */
    @Test void vkUploadFailureDoesNotPost() throws Exception {
        String token = "vk1.a." + "b".repeat(80);
        for (String upload : List.of("{\"server\":1,\"photo\":\"[]\",\"hash\":\"\"}", "not json")) {
            HttpClient http = mock(HttpClient.class);
            when(http.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenAnswer(invocation -> {
                String uri = invocation.<HttpRequest>getArgument(0).uri().toString();
                return uri.endsWith("getWallUploadServer") ? response(200, "{\"response\":{\"upload_url\":\"https://pu.vk.com/u\"}}")
                        : response(200, upload);
            });
            Outcome outcome = new VkWallClient(mapper, token, "", http).send("212345678", Map.of("message", "Подборка"), IMAGE);
            assertThat(outcome.status()).isEqualTo("FAILED");
            assertThat(outcome.detail()).contains("картинк").doesNotContain(token);
            assertThat(captured(http)).extracting(request -> request.uri().toString()).doesNotContain("https://api.vk.com/method/wall.post");
        }
        HttpClient limited = mock(HttpClient.class);
        HttpResponse<String> tooFast = response(200, "{\"error\":{\"error_code\":6}}");
        when(limited.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(tooFast);
        Outcome retry = new VkWallClient(mapper, token, "", limited).send("212345678", Map.of("message", "Подборка"), IMAGE);
        assertThat(retry.status()).isEqualTo("RETRY");
        assertThat(retry.retryAt()).isNotNull();
        verify(limited, times(1)).send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
    }

    /** Короткая подборка помещается в подпись к фото; длинная уходит следом отдельным сообщением. */
    @Test void telegramDigestRespectsCaptionLimit() {
        GameDigest digest = new GameDigest(mock(JdbcTemplate.class), "https://new.ttg.club");
        List<DigestMessage> short1 = digest.messages(games(1), Platform.TELEGRAM, true);
        assertThat(short1).singleElement().satisfies(message -> {
            assertThat(message.withImage()).isTrue();
            assertThat(message.gameCount()).isEqualTo(1);
            assertThat(message.payload()).containsOnlyKeys("caption", "parse_mode");
            assertThat(message.payload().get("caption").toString()).startsWith("Ищете компанию").contains("Игра 0", "Подробнее</a>");
        });
        List<GameEntry> eight = games(8);
        List<DigestMessage> text = digest.messages(eight, Platform.TELEGRAM);
        List<DigestMessage> split = digest.messages(eight, Platform.TELEGRAM, true);
        assertThat(split).hasSize(2);
        String caption = split.getFirst().payload().get("caption").toString();
        String rest = split.get(1).payload().get("text").toString();
        assertThat(split.getFirst().withImage()).isTrue();
        assertThat(split.getFirst().gameCount()).isZero();
        assertThat(caption).startsWith("Ищете компанию").doesNotContain("Игра 0");
        assertThat(GameDigest.visibleLength(caption)).isLessThanOrEqualTo(GameDigest.TELEGRAM_CAPTION_LENGTH);
        assertThat(split.get(1).withImage()).isFalse();
        assertThat(split.get(1).gameCount()).isEqualTo(8);
        assertThat(split.get(1).payload()).containsEntry("parse_mode", "HTML").containsKey("link_preview_options");
        assertThat(rest).startsWith("Игра 0");
        assertThat(caption + "\n\n" + rest).isEqualTo(text.getFirst().payload().get("text"));
        assertThat(GameDigest.visibleLength("<a href=\"https://x\">Подробнее</a> &#38;")).isEqualTo("Подробнее &".length());
    }

    /** Discord и VK получают тот же текст одним сообщением, отмеченным для картинки. */
    @Test void discordAndVkDigestKeepTextWithImage() {
        GameDigest digest = new GameDigest(mock(JdbcTemplate.class), "https://new.ttg.club");
        List<GameEntry> eight = games(8);
        for (Platform platform : List.of(Platform.DISCORD, Platform.VK)) {
            DigestMessage plain = digest.messages(eight, platform).getFirst();
            assertThat(digest.messages(eight, platform, true)).singleElement().satisfies(message -> {
                assertThat(message.withImage()).isTrue();
                assertThat(message.payload()).isEqualTo(plain.payload());
            });
            assertThat(plain.withImage()).isFalse();
        }
    }

    /** Картинка прикрепляется только к отмеченной части выпуска. */
    @Test void scheduledDigestSendsImageWithMarkedMessage() throws Exception {
        Fixture fixture = new Fixture(Platform.TELEGRAM);
        when(fixture.images.load(PATH)).thenReturn(IMAGE);
        List<DigestMessage> parts = List.of(new DigestMessage(Map.of("caption", "Вступление"), 0, true), new DigestMessage(Map.of("text", "Игры"), 1));
        when(fixture.digest.messages(fixture.games, Platform.TELEGRAM, true)).thenReturn(parts);
        when(fixture.telegram.send(anyString(), anyMap(), any())).thenReturn(sent("1"), sent("2"));
        fixture.scheduler.publish(fixture.delivery);
        verify(fixture.telegram).send("chat", parts.get(0).payload(), IMAGE);
        verify(fixture.telegram).send("chat", parts.get(1).payload(), null);
        verify(fixture.service).finish(eq(fixture.delivery), argThat(outcome -> outcome.status().equals("SENT")
                && outcome.detail().equals("Опубликовано сообщений: 2")), eq(1), any());
    }

    /** Отказ платформы от сообщения с картинкой ничего не публикует: выпуск уходит без картинки с пояснением. */
    @Test void rejectedImageFallsBackToText() throws Exception {
        Fixture fixture = new Fixture(Platform.DISCORD);
        when(fixture.images.load(PATH)).thenReturn(IMAGE);
        DigestMessage withImage = new DigestMessage(Map.of("content", "Подборка"), 1, true);
        DigestMessage plain = new DigestMessage(Map.of("content", "Подборка"), 1);
        when(fixture.digest.messages(fixture.games, Platform.DISCORD, true)).thenReturn(List.of(withImage));
        when(fixture.digest.messages(fixture.games, Platform.DISCORD)).thenReturn(List.of(plain));
        when(fixture.discord.send(anyString(), anyMap(), eq(IMAGE))).thenReturn(new Outcome("FAILED", "Discord отклонил отправку (HTTP 400)", null, null));
        when(fixture.discord.send(anyString(), anyMap(), isNull())).thenReturn(sent("123456789012345678"));
        fixture.scheduler.publish(fixture.delivery);
        verify(fixture.service).finish(eq(fixture.delivery), argThat(outcome -> outcome.status().equals("SENT")
                && outcome.messageId().equals("123456789012345678")
                && outcome.detail().equals("Без картинки (Discord отклонил отправку (HTTP 400)). Опубликовано сообщений: 1")), eq(1), any());
    }

    /** Недоступная картинка не останавливает выпуск; неоднозначный исход и лимит не повторяются без картинки. */
    @Test void unavailableImageAndAmbiguousOutcomes() throws Exception {
        Fixture missing = new Fixture(Platform.DISCORD);
        when(missing.images.load(PATH)).thenThrow(new PublicationImages.Unavailable("картинка не найдена на сайте (HTTP 404)"));
        when(missing.digest.messages(missing.games, Platform.DISCORD)).thenReturn(List.of(new DigestMessage(Map.of("content", "Подборка"), 1)));
        when(missing.discord.send(anyString(), anyMap(), isNull())).thenReturn(sent("123456789012345678"));
        missing.scheduler.publish(missing.delivery);
        verify(missing.service).finish(eq(missing.delivery), argThat(outcome -> outcome.status().equals("SENT") && outcome.detail()
                .equals("Без картинки (Картинка недоступна: картинка не найдена на сайте (HTTP 404)). Опубликовано сообщений: 1")), eq(1), any());
        for (Outcome ambiguous : List.of(new Outcome("UNKNOWN", "Нет подтверждения", null, null),
                new Outcome("RETRY", "Лимит", null, java.time.Instant.now().plusSeconds(5)))) {
            Fixture fixture = new Fixture(Platform.DISCORD);
            when(fixture.images.load(PATH)).thenReturn(IMAGE);
            when(fixture.digest.messages(fixture.games, Platform.DISCORD, true)).thenReturn(List.of(new DigestMessage(Map.of("content", "Подборка"), 1, true)));
            when(fixture.discord.send(anyString(), anyMap(), any())).thenReturn(ambiguous);
            fixture.scheduler.publish(fixture.delivery);
            verify(fixture.discord, times(1)).send(anyString(), anyMap(), any());
            verify(fixture.service).finish(eq(fixture.delivery), eq(ambiguous), eq(1), any());
        }
    }

    /** Составное пояснение длиннее столбца журнала сокращается, а не роняет запись результата. */
    @Test void longDetailIsLimited() {
        assertThat(DigestText.limit("а".repeat(400), 300)).hasSize(300).endsWith("…");
        assertThat(DigestText.limit("коротко", 300)).isEqualTo("коротко");
    }

    /** Планировщик с подменёнными платформами, каталогом и картинкой. */
    private static final class Fixture {
        final PublicationService service = mock(PublicationService.class);
        final GameDigest digest = mock(GameDigest.class);
        final PublicationSecrets secrets = mock(PublicationSecrets.class);
        final DiscordWebhookClient discord = mock(DiscordWebhookClient.class);
        final TelegramBotClient telegram = mock(TelegramBotClient.class);
        final PublicationImages images = mock(PublicationImages.class);
        final PublicationScheduler scheduler = new PublicationScheduler(service, digest,
                new PublicationSender(secrets, discord, telegram, mock(VkWallClient.class)), images);
        final Delivery delivery;
        final List<GameEntry> games = games(1);

        Fixture(Platform platform) {
            delivery = new Delivery(UUID.randomUUID(), UUID.randomUUID(), 0, "encrypted", java.time.Instant.now(), 1, platform, PATH);
            when(service.current(delivery)).thenReturn(true);
            when(digest.preview()).thenReturn(games);
            when(secrets.decrypt(eq(platform), anyString())).thenReturn(platform == Platform.TELEGRAM ? "chat" : "webhook");
        }
    }

    /** Игры подборки с короткими полями. */
    private static List<GameEntry> games(int count) {
        return IntStream.range(0, count).mapToObj(index -> {
            UUID gameId = UUID.randomUUID();
            return new GameEntry(gameId, "Игра " + index + " " + "с длинным названием ".repeat(3), "Dungeons & Dragons 5e",
                    2, 5, "https://new.ttg.club/games/" + gameId, "Фэнтези, Приключения, Детектив", "");
        }).toList();
    }

    /** Подтверждённая отправка. */
    private static Outcome sent(String messageId) { return new Outcome("SENT", "Опубликовано", messageId, null); }

    /** Ответ сервера с кодом и телом. */
    private static HttpResponse<String> response(int status, String body) {
        HttpResponse<String> response = mock();
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        return response;
    }

    /** Загрузчик картинок, которому сайт отвечает заданным кодом. */
    private static PublicationImages images(int status, byte[] body) throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<InputStream> response = mock();
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(new ByteArrayInputStream(body));
        when(http.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any())).thenReturn(response);
        return new PublicationImages("https://new.ttg.club", http);
    }

    /** Все запросы к подменённому клиенту по порядку. */
    private static List<HttpRequest> captured(HttpClient http) throws Exception {
        ArgumentCaptor<HttpRequest> requests = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http, atLeastOnce()).send(requests.capture(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
        return requests.getAllValues();
    }

    /** Часть multipart-тела. */
    private record Part(String filename, String type, byte[] data) {
        String text() { return new String(data, StandardCharsets.UTF_8); }
    }

    /** Разбирает multipart-тело по границе из заголовка запроса. */
    private static Map<String, Part> parts(HttpRequest request) throws Exception {
        String contentType = request.headers().firstValue("Content-Type").orElseThrow();
        assertThat(contentType).startsWith("multipart/form-data; boundary=");
        String boundary = "--" + contentType.substring(contentType.indexOf('=') + 1);
        String body = new String(bytes(request), StandardCharsets.ISO_8859_1);
        assertThat(body).endsWith(boundary + "--\r\n");
        Map<String, Part> parts = new LinkedHashMap<>();
        for (String chunk : body.substring(0, body.lastIndexOf(boundary + "--")).split(java.util.regex.Pattern.quote(boundary))) {
            if (chunk.isEmpty()) continue;
            int separator = chunk.indexOf("\r\n\r\n");
            String headers = chunk.substring(2, separator);
            byte[] data = chunk.substring(separator + 4, chunk.length() - 2).getBytes(StandardCharsets.ISO_8859_1);
            String name = headers.replaceAll("(?s).*?; name=\"([^\"]+)\".*", "$1");
            String filename = headers.contains("filename=\"") ? headers.replaceAll("(?s).*filename=\"([^\"]+)\".*", "$1") : null;
            String type = headers.contains("Content-Type: ") ? headers.replaceAll("(?s).*Content-Type: ([^\r\n]+).*", "$1") : null;
            parts.put(name, new Part(filename, type, data));
        }
        return parts;
    }

    /** Разбирает тело формы запроса к API VK. */
    private static Map<String, String> form(HttpRequest request) throws Exception {
        return Arrays.stream(new String(bytes(request), StandardCharsets.UTF_8).split("&")).map(field -> field.split("=", 2))
                .collect(Collectors.toMap(field -> field[0], field -> URLDecoder.decode(field[1], StandardCharsets.UTF_8),
                        (first, second) -> second, LinkedHashMap::new));
    }

    /** Читает тело запроса через стандартный потоковый контракт JDK. */
    private static byte[] bytes(HttpRequest request) throws Exception {
        CompletableFuture<byte[]> completed = new CompletableFuture<>();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>() {
            /** Запрашивает всё конечное тело. */
            public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            /** Копирует следующий фрагмент. */
            public void onNext(ByteBuffer buffer) { while (buffer.hasRemaining()) bytes.write(buffer.get()); }
            /** Передаёт ошибку тесту. */
            public void onError(Throwable failure) { completed.completeExceptionally(failure); }
            /** Возвращает тело целиком. */
            public void onComplete() { completed.complete(bytes.toByteArray()); }
        });
        return completed.get(5, java.util.concurrent.TimeUnit.SECONDS);
    }
}
