package club.ttg.findgame.publications;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
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
    static final String API = "https://api.vk.com/method/";
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
    Outcome send(String groupId, Map<String, Object> payload, ImageFile image) {
        if (!configured()) return new Outcome("FAILED", "Ключ сообщества ВКонтакте не настроен на сервере", null, null);
        // Картинка загружается до записи: сбой загрузки ничего не публикует, и повтор не создаст дубль.
        Upload upload = image == null ? null : uploadPhoto(groupId, image);
        if (upload != null && upload.failure() != null) return upload.failure();
        try {
            Map<String, String> form = new LinkedHashMap<>();
            form.put("owner_id", "-" + groupId);
            form.put("from_group", "1");
            form.put("message", payload.get("message").toString());
            if (upload != null) form.put("attachments", upload.attachment());
            HttpResponse<String> response = call("wall.post", form);
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

    /**
     * Загружает фото в альбом сообщества: сначала штатно для стены, а если ключ сообщества туда не пускает
     * (ошибка 27), — через альбом сообщений, как обложки новостей в core-api. Фото всё равно принадлежит сообществу.
     */
    private Upload uploadPhoto(String groupId, ImageFile image) {
        try {
            Upload wall = uploadPhoto(image, "photos.getWallUploadServer", "photos.saveWallPhoto", Map.of("group_id", groupId));
            if (wall != null) return wall;
            Upload messages = uploadPhoto(image, "photos.getMessagesUploadServer", "photos.saveMessagesPhoto", Map.of());
            return messages != null ? messages
                    : Upload.failed("ВКонтакте не дал загрузить картинку; проверьте право ключа сообщества на фотографии");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Upload.failed("Отправка прервана до публикации записи");
        } catch (IOException | RuntimeException exception) {
            return Upload.failed("Не удалось загрузить картинку во ВКонтакте");
        }
    }

    /** Три шага VK: адрес загрузки, файл, сохранение фото; null — VK не выдал адрес, можно попробовать другой путь. */
    private Upload uploadPhoto(ImageFile image, String serverMethod, String saveMethod, Map<String, String> group)
            throws IOException, InterruptedException {
        JsonNode server = mapper.readTree(call(serverMethod, group).body());
        if (server.has("error")) return rateLimited(server) ? Upload.retry() : null;
        String uploadUrl = server.path("response").path("upload_url").asText("");
        if (!uploadUrl.startsWith("https://")) return null;
        MultipartBody photo = new MultipartBody().file("photo", image);
        HttpResponse<String> uploaded = client.send(HttpRequest.newBuilder(URI.create(uploadUrl)).timeout(Duration.ofSeconds(30))
                .header("Content-Type", photo.contentType()).POST(photo.publisher()).build(), HttpResponse.BodyHandlers.ofString());
        JsonNode file = mapper.readTree(uploaded.body());
        String photoData = file.path("photo").asText("");
        // Пустой список — VK отверг файл: обычно размер или пропорции картинки.
        if (uploaded.statusCode() != 200 || photoData.isBlank() || photoData.equals("[]")) {
            return Upload.failed("ВКонтакте не принял картинку; проверьте её размер и пропорции");
        }
        Map<String, String> save = new LinkedHashMap<>(group);
        save.put("server", file.path("server").asText(""));
        save.put("photo", photoData);
        save.put("hash", file.path("hash").asText(""));
        JsonNode saved = mapper.readTree(call(saveMethod, save).body());
        if (saved.has("error")) return rateLimited(saved) ? Upload.retry() : Upload.failed("ВКонтакте не сохранил картинку");
        JsonNode stored = saved.path("response").path(0);
        if (!stored.path("id").isIntegralNumber() || !stored.path("owner_id").isIntegralNumber()) {
            return Upload.failed("ВКонтакте не сохранил картинку");
        }
        // Ключ доступа VK выдаёт для фото из закрытого альбома сообщений; без него фото в записи не откроется.
        String accessKey = stored.path("access_key").asText("");
        return new Upload("photo" + stored.path("owner_id").asLong() + "_" + stored.path("id").asLong()
                + (accessKey.matches("[A-Za-z0-9]+") ? "_" + accessKey : ""), null);
    }

    /** Код 6 означает, что запрос отклонён до выполнения: загрузку можно повторить позже. */
    private static boolean rateLimited(JsonNode response) {
        return response.path("error").path("error_code").asInt(0) == 6;
    }

    /** Вызывает метод API: ключ и версия передаются в теле формы, адрес фиксирован. */
    private HttpResponse<String> call(String method, Map<String, String> parameters) throws IOException, InterruptedException {
        Map<String, String> form = new LinkedHashMap<>(parameters);
        form.put("access_token", token);
        form.put("v", API_VERSION);
        String body = form.entrySet().stream().map(field -> field.getKey() + "="
                + URLEncoder.encode(field.getValue(), StandardCharsets.UTF_8)).collect(Collectors.joining("&"));
        HttpRequest request = HttpRequest.newBuilder(URI.create(API + method))
                .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** Освобождает соединения при завершении работы приложения. */
    @PreDestroy
    public void shutdown() { client.shutdown(); }

    /** Итог загрузки фото: строка вложения для записи или безопасная причина отказа. */
    private record Upload(String attachment, Outcome failure) {
        /** Отказ без повтора: выпуск уйдёт без картинки. */
        static Upload failed(String detail) { return new Upload(null, new Outcome("FAILED", detail, null, null)); }
        /** Ограничение частоты: запись ещё не отправлена, повтор не создаст дубль. */
        static Upload retry() {
            return new Upload(null, new Outcome("RETRY", "Ожидает снятия ограничения ВКонтакте", null,
                    Instant.now().plusSeconds(RATE_LIMIT_DELAY_SECONDS)));
        }
    }
}
