package club.ttg.findgame.publications;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import static club.ttg.findgame.publications.PublicationModels.*;

/** Отправляет через существующего бота, не раскрывая токен и ID чата в ошибках. */
@Component
public class TelegramBotClient {
    private final ObjectMapper mapper;
    private final String token;
    private final HttpClient client;

    /** Использует то же имя серверной переменной, что и бэкенд сайта. */
    @Autowired
    public TelegramBotClient(ObjectMapper mapper, @Value("${telegram.bot-token:}") String token) {
        this(mapper, token, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build());
    }

    /** Позволяет проверить настоящий HTTP-запрос без обращения к Telegram. */
    TelegramBotClient(ObjectMapper mapper, String token, HttpClient client) {
        this.mapper = mapper;
        this.token = token.trim();
        this.client = client;
    }

    /** Отсутствующая настройка отключает только отправку Telegram. */
    public boolean configured() {
        return token.matches("[1-9][0-9]{4,19}:[A-Za-z0-9_-]{30,200}");
    }

    /** Передаёт ID в теле запроса, отправляет одно сообщение (с картинкой — sendPhoto) и не повторяет неоднозначную доставку. */
    Outcome send(String chatId, Map<String, Object> payload, ImageFile image) {
        if (!configured()) return new Outcome("FAILED", "Бот Telegram не настроен на сервере", null, null);
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("https://api.telegram.org/bot" + token
                    + (image == null ? "/sendMessage" : "/sendPhoto"))).timeout(Duration.ofSeconds(15));
            if (image == null) {
                Map<String, Object> body = new HashMap<>(payload);
                body.put("chat_id", chatId);
                builder.header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            } else {
                MultipartBody body = new MultipartBody().field("chat_id", chatId);
                payload.forEach((name, value) -> body.field(name, value instanceof String text ? text : mapper.writeValueAsString(value)));
                builder.header("Content-Type", body.contentType()).POST(body.file("photo", image).publisher());
            }
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return interpret(response.statusCode(), response.body(), Instant.now(), image != null);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new Outcome("UNKNOWN", "Отправка прервана; проверьте канал Telegram", null, null);
        } catch (IOException | RuntimeException exception) {
            return new Outcome("UNKNOWN", "Нет подтверждения доставки; проверьте канал Telegram", null, null);
        }
    }

    /** Читает ответ на текстовое сообщение. */
    Outcome interpret(int status, String body, Instant now) { return interpret(status, body, now, false); }

    /** Читает только подтверждение и лимит; чужое описание ошибки не попадает в журнал. */
    Outcome interpret(int status, String body, Instant now, boolean photo) {
        if (status >= 500) return new Outcome("UNKNOWN", "Ошибка Telegram; доставка не подтверждена", null, null);
        try {
            var response = mapper.readTree(body);
            if (status == 200 && response.path("ok").asBoolean(false)) {
                var messageId = response.path("result").path("message_id");
                if (messageId.isIntegralNumber() && messageId.asLong(0) > 0) {
                    return new Outcome("SENT", "Опубликовано", messageId.asText(), null);
                }
                return new Outcome("UNKNOWN", "Telegram не вернул подтверждение сообщения", null, null);
            }
            int errorCode = response.path("error_code").asInt(status);
            if (status == 429 || errorCode == 429) {
                var seconds = response.path("parameters").path("retry_after");
                if (!seconds.isIntegralNumber() || seconds.asLong(0) <= 0 || seconds.asLong(0) > 86400) {
                    return new Outcome("FAILED", "Telegram ограничил частоту; время повтора неизвестно", null, null);
                }
                return new Outcome("RETRY", "Ожидает снятия ограничения Telegram", null, now.plusSeconds(seconds.asLong() + 1));
            }
            if (response.path("parameters").has("migrate_to_chat_id")) {
                return new Outcome("FAILED", "Группа преобразована в супергруппу; обновите ID чата в настройках канала", null, null);
            }
            if (status == 401 || errorCode == 401) return new Outcome("FAILED", "Telegram отклонил токен бота; проверьте настройку сервера", null, null);
            if (status == 400 || status == 403 || errorCode == 400 || errorCode == 403) {
                return new Outcome("FAILED", photo
                        ? "Telegram отклонил сообщение с картинкой; проверьте картинку, ID чата и право бота публиковать сообщения"
                        : "Telegram отклонил отправку; проверьте ID чата и право бота публиковать сообщения", null, null);
            }
            if (status == 200) return new Outcome("UNKNOWN", "Telegram не подтвердил доставку; проверьте канал", null, null);
            return new Outcome("FAILED", "Telegram отклонил отправку (HTTP " + status + ")", null, null);
        } catch (RuntimeException exception) {
            return new Outcome("UNKNOWN", "Не удалось разобрать подтверждение Telegram", null, null);
        }
    }

    /** Освобождает соединения при завершении работы приложения. */
    @PreDestroy
    public void shutdown() { client.shutdown(); }
}
