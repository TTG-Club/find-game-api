package club.ttg.findgame.publications;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLDecoder;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.stream.Collectors;
import static club.ttg.findgame.publications.PublicationModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Проверяет HTTP-контракт стены VK, безопасность ответов и формат общего выпуска. */
class VkWallClientTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private static final String TOKEN = "vk1.a." + "b".repeat(80);

    /** Ключ передаётся в теле формы, адрес фиксирован, запись публикуется от имени сообщества. */
    @Test void sendsOneFormRequestWithTokenOutsideUrl() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> response = mock();
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"response\":{\"post_id\":42}}");
        when(http.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(response);
        VkWallClient client = new VkWallClient(mapper, TOKEN, "", http);
        Outcome outcome = client.send("212345678", Map.of("message", "Игры & приключения\nПодробнее: https://new.ttg.club/games"));
        assertThat(outcome.status()).isEqualTo("SENT");
        assertThat(outcome.messageId()).isEqualTo("42");
        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http, times(1)).send(request.capture(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
        assertThat(request.getValue().uri().toString()).isEqualTo("https://api.vk.com/method/wall.post").doesNotContain(TOKEN);
        assertThat(request.getValue().method()).isEqualTo("POST");
        assertThat(request.getValue().timeout()).contains(java.time.Duration.ofSeconds(15));
        assertThat(request.getValue().headers().firstValue("Content-Type")).contains("application/x-www-form-urlencoded");
        assertThat(form(request.getValue())).containsExactly(
                Map.entry("owner_id", "-212345678"), Map.entry("from_group", "1"),
                Map.entry("message", "Игры & приключения\nПодробнее: https://new.ttg.club/games"),
                Map.entry("access_token", TOKEN), Map.entry("v", "5.199"));
        client.shutdown();
        verify(http).shutdown();
    }

    /** Сетевой сбой не повторяет запрос и не выдаёт ключ или ID; без ключа запрос не выполняется. */
    @Test void unknownDeliveryIsNotRetriedAndSecretsStayPrivate() throws Exception {
        HttpClient http = mock(HttpClient.class);
        when(http.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenThrow(new IOException(TOKEN + "/212345678"));
        VkWallClient client = new VkWallClient(mapper, TOKEN, "", http);
        Outcome outcome = client.send("212345678", Map.of("message", "Проверка"));
        assertThat(outcome.status()).isEqualTo("UNKNOWN");
        assertThat(outcome.detail()).doesNotContain(TOKEN, "212345678");
        verify(http, times(1)).send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
        for (String token : List.of("", "   ", "short", TOKEN + "&v=1")) {
            VkWallClient unconfigured = new VkWallClient(mapper, token, "", http);
            assertThat(unconfigured.configured()).isFalse();
            assertThat(unconfigured.send("212345678", Map.of("message", "Проверка")).status()).isEqualTo("FAILED");
        }
        assertThat(new VkWallClient(mapper, " " + TOKEN + " ", "", http).configured()).isTrue();
        verifyNoMoreInteractions(http);
    }

    /** VK отвечает об ошибке кодом с HTTP 200: повтор только для частоты запросов, текст ошибки не раскрывается. */
    @Test void interpretsAcknowledgementsAndSafeErrors() {
        VkWallClient client = new VkWallClient(mapper, TOKEN, "", mock(HttpClient.class));
        Instant now = Instant.parse("2026-09-21T16:00:00Z");
        assertThat(client.interpret(200, "{\"response\":{\"post_id\":42}}", now).messageId()).isEqualTo("42");
        assertThat(client.interpret(200, "{\"response\":{\"post_id\":0}}", now).status()).isEqualTo("UNKNOWN");
        assertThat(client.interpret(200, "{\"response\":{}}", now).status()).isEqualTo("UNKNOWN");
        assertThat(client.interpret(200, "not json", now).status()).isEqualTo("UNKNOWN");
        assertThat(client.interpret(502, TOKEN, now).status()).isEqualTo("UNKNOWN");
        assertThat(client.interpret(302, "{}", now).status()).isEqualTo("FAILED");
        Outcome limited = client.interpret(200, error(6), now);
        assertThat(limited.status()).isEqualTo("RETRY");
        assertThat(limited.retryAt()).isEqualTo(now.plusSeconds(5));
        for (int code : List.of(1, 10)) assertThat(client.interpret(200, error(code), now).status()).isEqualTo("UNKNOWN");
        assertThat(client.interpret(200, error(5), now).detail()).contains("ключ сообщества");
        assertThat(client.interpret(200, error(9), now).detail()).contains("число записей");
        assertThat(client.interpret(200, error(214), now).detail()).contains("ID сообщества");
        assertThat(client.interpret(200, error(999), now).detail()).contains("код 999");
        for (int code : List.of(5, 7, 9, 14, 15, 27, 100, 203, 214, 999)) {
            Outcome outcome = client.interpret(200, error(code), now);
            assertThat(outcome.status()).isEqualTo("FAILED");
            assertThat(outcome.retryAt()).isNull();
            assertThat(outcome.detail()).doesNotContain(TOKEN);
        }
    }

    /** Сообщество по умолчанию берётся из VK_GROUP_ID только в виде числа, как в core-api. */
    @Test void defaultGroupIdAcceptsOnlyNumbers() {
        HttpClient http = mock(HttpClient.class);
        assertThat(new VkWallClient(mapper, TOKEN, " 212345678 ", http).defaultGroupId()).isEqualTo("212345678");
        assertThat(new VkWallClient(mapper, TOKEN, "-212345678", http).defaultGroupId()).isEqualTo("-212345678");
        for (String invalid : List.of("", "0", "club212345678", "https://vk.com/club1")) {
            assertThat(new VkWallClient(mapper, TOKEN, invalid, http).defaultGroupId()).isEmpty();
        }
        verifyNoInteractions(http);
    }

    /** Подборка VK — обычный текст с полными адресами: восемь игр, ссылка на каталог, без описаний и разметки. */
    @Test void vkDigestContainsEightGamesAsPlainText() {
        GameDigest digest = new GameDigest(mock(JdbcTemplate.class), "https://new.ttg.club");
        List<GameEntry> games = java.util.stream.IntStream.range(0, 8).mapToObj(index -> {
            UUID gameId = UUID.randomUUID();
            return new GameEntry(gameId, "Игра @durov & [id1|друзья] " + index, "D&D", 2, 5,
                    "https://new.ttg.club/games/" + gameId, "Фэнтези", "СЕКРЕТНОЕ ОПИСАНИЕ");
        }).toList();
        List<DigestMessage> messages = digest.messages(games, Platform.VK);
        assertThat(messages).singleElement().satisfies(message -> assertThat(message.gameCount()).isEqualTo(8));
        Map<String, Object> payload = messages.getFirst().payload();
        assertThat(payload).containsOnlyKeys("message");
        String text = payload.get("message").toString();
        assertThat(text).startsWith("Ищете компанию для приключений? 🎲\n\n")
                .contains("игры с открытым набором на new.ttg.club.", "Игра @\u2060durov & id1 друзья 0", "D&D · Занято 2/5 · Свободно 3", "Жанры: Фэнтези")
                .doesNotContain("СЕКРЕТНОЕ ОПИСАНИЕ", "&#38;", "<a", "[", "](", "|", "@durov");
        assertThat(text.substring(text.lastIndexOf("\n\n")))
                .isEqualTo("\n\nХотите больше вариантов? Загляните в полный список игр на сайте (https://new.ttg.club/games)"
                        + " — там вас ждут другие приключения и новые знакомства. Будем рады каждому!");
        assertThat(text.length()).isLessThanOrEqualTo(2000);
        games.forEach(game -> assertThat(text).containsOnlyOnce("\nПодробнее: " + game.url()));
    }

    /** Строит тело ошибки VK: HTTP 200 и код в поле error. */
    private static String error(int code) {
        return "{\"error\":{\"error_code\":" + code + ",\"error_msg\":\"" + TOKEN + "\"}}";
    }

    /** Разбирает тело формы реального HTTP-запроса, сохраняя порядок полей. */
    private static Map<String, String> form(HttpRequest request) throws Exception {
        return Arrays.stream(body(request).split("&")).map(field -> field.split("=", 2)).collect(Collectors.toMap(
                field -> field[0], field -> URLDecoder.decode(field[1], StandardCharsets.UTF_8), (first, second) -> second, LinkedHashMap::new));
    }

    /** Читает тело реального HTTP-запроса через стандартный потоковый контракт JDK. */
    private static String body(HttpRequest request) throws Exception {
        CompletableFuture<String> completed = new CompletableFuture<>();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>() {
            /** Запрашивает всё конечное тело формы. */
            public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            /** Копирует следующий фрагмент для проверки. */
            public void onNext(ByteBuffer buffer) { while (buffer.hasRemaining()) bytes.write(buffer.get()); }
            /** Передаёт ошибку тесту. */
            public void onError(Throwable failure) { completed.completeExceptionally(failure); }
            /** Возвращает тело в UTF-8. */
            public void onComplete() { completed.complete(bytes.toString(StandardCharsets.UTF_8)); }
        });
        return completed.get(5, java.util.concurrent.TimeUnit.SECONDS);
    }
}
