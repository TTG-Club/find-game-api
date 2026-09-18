package club.ttg.findgame.publications;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import static club.ttg.findgame.publications.PublicationModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Проверяет HTTP-контракт бота, безопасность ответов и формат общего выпуска. */
class TelegramBotClientTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private static final String TOKEN = "123456789:" + "a".repeat(35);

    /** Токен используется только в фиксированном HTTPS-адресе, ID передаётся внутри JSON. */
    @Test void sendsOneRequestWithCorrectBodyAndRejectsRedirectResponse() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> response = mock();
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"ok\":true,\"result\":{\"message_id\":42}}");
        when(http.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(response);
        TelegramBotClient client = new TelegramBotClient(mapper, TOKEN, http);
        assertThat(client.send("-1001234567890", Map.of("text", "Проверка", "link_preview_options", Map.of("is_disabled", true))).status()).isEqualTo("SENT");
        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http, times(1)).send(request.capture(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
        assertThat(request.getValue().uri().toString()).isEqualTo("https://api.telegram.org/bot" + TOKEN + "/sendMessage");
        assertThat(request.getValue().timeout()).contains(java.time.Duration.ofSeconds(15));
        assertThat(mapper.readTree(body(request.getValue())).path("chat_id").asText()).isEqualTo("-1001234567890");
        assertThat(mapper.readTree(body(request.getValue())).has("allow_paid_broadcast")).isFalse();
        assertThat(client.interpret(302, "{}", Instant.now()).status()).isEqualTo("FAILED");
        client.shutdown();
        verify(http).shutdown();
    }

    /** Сетевой сбой не повторяет запрос и не выдаёт токен или ID. */
    @Test void unknownDeliveryIsNotRetriedAndSecretsStayPrivate() throws Exception {
        HttpClient http = mock(HttpClient.class);
        when(http.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenThrow(new IOException(TOKEN + "/-1001234567890"));
        TelegramBotClient client = new TelegramBotClient(mapper, TOKEN, http);
        Outcome outcome = client.send("-1001234567890", Map.of("text", "Проверка"));
        assertThat(outcome.status()).isEqualTo("UNKNOWN");
        assertThat(outcome.detail()).doesNotContain(TOKEN, "-1001234567890");
        verify(http, times(1)).send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
        TelegramBotClient unconfigured = new TelegramBotClient(mapper, "", http);
        assertThat(unconfigured.configured()).isFalse();
        assertThat(unconfigured.send("-1001234567890", Map.of("text", "Проверка")).status()).isEqualTo("FAILED");
        verifyNoMoreInteractions(http);
    }

    /** Повтор допустим только после явного ограничения; тело ошибки никогда не раскрывается. */
    @Test void interpretsAcknowledgementsAndSafeErrors() {
        TelegramBotClient client = new TelegramBotClient(mapper, TOKEN, mock(HttpClient.class));
        Instant now = Instant.parse("2026-09-18T16:00:00Z");
        assertThat(client.interpret(200, "{\"ok\":true,\"result\":{\"message_id\":42}}", now).messageId()).isEqualTo("42");
        assertThat(client.interpret(200, "{\"ok\":true}", now).status()).isEqualTo("UNKNOWN");
        assertThat(client.interpret(200, "not json", now).status()).isEqualTo("UNKNOWN");
        assertThat(client.interpret(500, TOKEN, now).status()).isEqualTo("UNKNOWN");
        Outcome limited = client.interpret(429, "{\"ok\":false,\"error_code\":429,\"parameters\":{\"retry_after\":30}}", now);
        assertThat(limited.status()).isEqualTo("RETRY");
        assertThat(limited.retryAt()).isEqualTo(now.plusSeconds(31));
        assertThat(client.interpret(429, "{\"parameters\":{\"retry_after\":0}}", now).status()).isEqualTo("FAILED");
        assertThat(client.interpret(400, "{\"parameters\":{\"migrate_to_chat_id\":-1001234567890}}", now).detail()).contains("обновите ID").doesNotContain("-1001234567890");
        for (int status : List.of(400, 401, 403)) {
            Outcome outcome = client.interpret(status, "{\"ok\":false,\"description\":\"" + TOKEN + "\"}", now);
            assertThat(outcome.status()).isEqualTo("FAILED");
            assertThat(outcome.detail()).doesNotContain(TOKEN);
        }
    }

    /** Подборка Telegram содержит восемь ссылок, экранирует HTML и полностью исключает описания. */
    @Test void telegramDigestContainsEightGamesWithoutDescriptions() {
        GameDigest digest = new GameDigest(mock(JdbcTemplate.class), "https://new.ttg.club");
        List<GameEntry> games = java.util.stream.IntStream.range(0, 8).mapToObj(index -> {
            UUID gameId = UUID.randomUUID();
            return new GameEntry(gameId, "Название & café © приключение " + index, "D&D", 2, 5,
                    "https://new.ttg.club/games/" + gameId, "Фэнтези", "СЕКРЕТНОЕ ОПИСАНИЕ");
        }).toList();
        List<DigestMessage> messages = digest.messages(games, Platform.TELEGRAM);
        assertThat(messages).hasSize(1);
        Map<String, Object> payload = messages.getFirst().payload();
        assertThat(payload).containsEntry("parse_mode", "HTML").containsEntry("link_preview_options", Map.of("is_disabled", true));
        String text = payload.get("text").toString();
        assertThat(text).contains("&#38;", "Игры с открытым набором на сайте new.ttg.club").doesNotContain("СЕКРЕТНОЕ ОПИСАНИЕ", "<blockquote>", "[Подробнее");
        assertThat(org.springframework.web.util.HtmlUtils.htmlUnescape(text.replaceAll("<[^>]*>", ""))).hasSizeLessThanOrEqualTo(4096);
        games.forEach(game -> assertThat(text).containsOnlyOnce(game.url()));
    }

    /** Читает тело реального HTTP-запроса через стандартный потоковый контракт JDK. */
    private static String body(HttpRequest request) throws Exception {
        CompletableFuture<String> completed = new CompletableFuture<>();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>() {
            /** Запрашивает всё конечное JSON-тело. */
            public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            /** Копирует следующий фрагмент для проверки. */
            public void onNext(ByteBuffer buffer) { while (buffer.hasRemaining()) bytes.write(buffer.get()); }
            /** Передаёт ошибку тесту. */
            public void onError(Throwable failure) { completed.completeExceptionally(failure); }
            /** Возвращает декодированный JSON. */
            public void onComplete() { completed.complete(bytes.toString(StandardCharsets.UTF_8)); }
        });
        return completed.get(5, java.util.concurrent.TimeUnit.SECONDS);
    }
}
