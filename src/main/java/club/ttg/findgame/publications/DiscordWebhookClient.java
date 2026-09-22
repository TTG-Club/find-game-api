package club.ttg.findgame.publications;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import static club.ttg.findgame.publications.PublicationModels.*;

/** Отправляет сообщение без перенаправлений и без утечки URL в исключения. */
@Component
public class DiscordWebhookClient {
    private final ObjectMapper mapper;
    private final HttpClient client;

    /** Использует JSON-кодек приложения. */
    @Autowired
    public DiscordWebhookClient(ObjectMapper mapper) {
        this(mapper, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build());
    }

    /** Позволяет проверить настоящий HTTP-запрос без обращения к Discord. */
    DiscordWebhookClient(ObjectMapper mapper, HttpClient client) { this.mapper = mapper; this.client = client; }

    /** Запрашивает подтверждение доставки; неоднозначный исход не повторяется автоматически. */
    Outcome send(String webhookUrl, Map<String, Object> payload, ImageFile image) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(webhookUrl + "?wait=true")).timeout(Duration.ofSeconds(15));
        if (image == null) {
            builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)));
        } else {
            // Текст и настройки идут частью payload_json, картинка — вложением под сообщением.
            MultipartBody body = new MultipartBody().json("payload_json", mapper.writeValueAsString(payload)).file("files[0]", image);
            builder.header("Content-Type", body.contentType()).POST(body.publisher());
        }
        HttpRequest request = builder.build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return interpret(response.statusCode(), response.body(), Instant.now());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new Outcome("UNKNOWN", "Отправка прервана; проверьте канал", null, null);
        } catch (IOException | RuntimeException exception) {
            return new Outcome("UNKNOWN", "Нет подтверждения доставки; проверьте канал", null, null);
        }
    }

    /** Освобождает соединения клиента при остановке приложения. */
    @PreDestroy
    public void shutdown() {
        client.shutdown();
    }

    /** Разбирает только необходимые поля ответа и не сохраняет его тело. */
    Outcome interpret(int status, String body, Instant now) {
        try {
            if (status == 200) {
                String messageId = mapper.readTree(body).path("id").asText();
                if (messageId.matches("[0-9]{17,20}")) return new Outcome("SENT", "Опубликовано", messageId, null);
                return new Outcome("UNKNOWN", "Discord не вернул подтверждение сообщения", null, null);
            }
            if (status == 429) {
                double seconds = mapper.readTree(body).path("retry_after").asDouble(0);
                if (!Double.isFinite(seconds) || seconds <= 0 || seconds > 86400) {
                    return new Outcome("FAILED", "Discord ограничил частоту; время повтора неизвестно", null, null);
                }
                return new Outcome("RETRY", "Ожидает снятия ограничения Discord", null,
                        now.plusMillis((long) Math.ceil(seconds * 1000) + 1000));
            }
            if (status >= 500) return new Outcome("UNKNOWN", "Ошибка Discord; доставка не подтверждена", null, null);
            return new Outcome("FAILED", "Discord отклонил отправку (HTTP " + status + ")", null, null);
        } catch (RuntimeException exception) {
            return new Outcome("UNKNOWN", "Не удалось разобрать подтверждение Discord", null, null);
        }
    }
}
