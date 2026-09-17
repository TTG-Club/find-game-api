package club.ttg.findgame.discord;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import static club.ttg.findgame.discord.PublicationModels.*;

/** Отправляет сообщение без перенаправлений и без утечки URL в исключения. */
@Component
public class DiscordWebhookClient {
    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    /** Использует JSON-кодек приложения. */
    public DiscordWebhookClient(ObjectMapper mapper) { this.mapper = mapper; }

    /** Запрашивает подтверждение доставки; неоднозначный исход не повторяется автоматически. */
    Outcome send(String webhookUrl, Map<String, Object> payload) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(webhookUrl + "?wait=true"))
                .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload))).build();
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
