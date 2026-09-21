package club.ttg.findgame.publications;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import static club.ttg.findgame.publications.PublicationModels.*;

/** Публикует запись на стене сообщества ключом доступа сообщества, как core-api публикует новости. */
@Component
public class VkWallClient {
    static final String ENDPOINT = "https://api.vk.com/method/wall.post";
    static final String API_VERSION = "5.199";
    /** Пауза после «слишком много запросов в секунду»: VK не сообщает точное время. */
    private static final int RATE_LIMIT_DELAY_SECONDS = 5;
    private final ObjectMapper mapper;
    private final String token;
    private final String groupId;
    private final HttpClient client;

    /** Использует те же имена серверных переменных, что и core-api. */
    @Autowired
    public VkWallClient(ObjectMapper mapper, @Value("${vk.access-token:}") String token,
                        @Value("${vk.group-id:}") String groupId) {
        this(mapper, token, groupId, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build());
    }

    /** Позволяет проверить настоящий HTTP-запрос без обращения к VK. */
    VkWallClient(ObjectMapper mapper, String token, String groupId, HttpClient client) {
        this.mapper = mapper;
        this.token = token.trim();
        this.groupId = groupId.trim();
        this.client = client;
    }

    /** Отсутствующая настройка отключает только отправку во ВКонтакте. */
    public boolean configured() {
        return token.matches("[A-Za-z0-9._-]{40,512}");
    }

    /** Сообщество из VK_GROUP_ID для канала без своего ID; пустая строка — не задано или не число. */
    String defaultGroupId() { return groupId.matches("-?[1-9][0-9]{0,11}") ? groupId : ""; }

    /** Передаёт ключ в теле формы, а не в адресе, и не повторяет неоднозначную доставку. */
    Outcome send(String groupId, Map<String, Object> payload) {
        if (!configured()) return new Outcome("FAILED", "Ключ сообщества ВКонтакте не настроен на сервере", null, null);
        try {
            Map<String, String> form = new LinkedHashMap<>();
            form.put("owner_id", "-" + groupId);
            form.put("from_group", "1");
            form.put("message", payload.get("message").toString());
            form.put("access_token", token);
            form.put("v", API_VERSION);
            String body = form.entrySet().stream().map(field -> field.getKey() + "="
                    + URLEncoder.encode(field.getValue(), StandardCharsets.UTF_8)).collect(Collectors.joining("&"));
            HttpRequest request = HttpRequest.newBuilder(URI.create(ENDPOINT))
                    .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return interpret(response.statusCode(), response.body(), Instant.now());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new Outcome("UNKNOWN", "Отправка прервана; проверьте стену сообщества", null, null);
        } catch (IOException | RuntimeException exception) {
            return new Outcome("UNKNOWN", "Нет подтверждения доставки; проверьте стену сообщества", null, null);
        }
    }

    /** VK сообщает об ошибке кодом в теле ответа с HTTP 200; текст ошибки не попадает в журнал. */
    Outcome interpret(int status, String body, Instant now) {
        if (status >= 500) return new Outcome("UNKNOWN", "Ошибка ВКонтакте; доставка не подтверждена", null, null);
        if (status != 200) return new Outcome("FAILED", "ВКонтакте отклонил отправку (HTTP " + status + ")", null, null);
        try {
            var response = mapper.readTree(body);
            if (response.has("error")) {
                int code = response.path("error").path("error_code").asInt(0);
                return switch (code) {
                    // Запрос отклонён до выполнения, поэтому повтор не создаст второй записи.
                    case 6 -> new Outcome("RETRY", "Ожидает снятия ограничения ВКонтакте", null,
                            now.plusSeconds(RATE_LIMIT_DELAY_SECONDS));
                    // Внутренняя ошибка VK: запись могла появиться на стене.
                    case 1, 10 -> new Outcome("UNKNOWN", "Ошибка ВКонтакте; доставка не подтверждена", null, null);
                    case 5 -> new Outcome("FAILED", "ВКонтакте отклонил ключ сообщества; проверьте настройку сервера", null, null);
                    case 9 -> new Outcome("FAILED", "ВКонтакте ограничил число записей; попробуйте позже", null, null);
                    case 14 -> new Outcome("FAILED", "ВКонтакте запросил капчу; попробуйте позже", null, null);
                    case 7, 15, 27, 100, 203, 214 -> new Outcome("FAILED",
                            "ВКонтакте отклонил отправку; проверьте ID сообщества и право ключа на стену", null, null);
                    default -> new Outcome("FAILED", "ВКонтакте отклонил отправку (код " + code + ")", null, null);
                };
            }
            var postId = response.path("response").path("post_id");
            if (postId.isIntegralNumber() && postId.asLong(0) > 0) {
                return new Outcome("SENT", "Опубликовано", postId.asText(), null);
            }
            return new Outcome("UNKNOWN", "ВКонтакте не вернул подтверждение записи", null, null);
        } catch (RuntimeException exception) {
            return new Outcome("UNKNOWN", "Не удалось разобрать подтверждение ВКонтакте", null, null);
        }
    }

    /** Освобождает соединения при завершении работы приложения. */
    @PreDestroy
    public void shutdown() { client.shutdown(); }
}
