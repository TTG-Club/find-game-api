package club.ttg.findgame.publications;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import static club.ttg.findgame.publications.PublicationModels.*;

/** Тело multipart/form-data для отправки картинки стандартным HTTP-клиентом JDK. */
final class MultipartBody {
    private final String boundary = "ttg-" + UUID.randomUUID();
    private final ByteArrayOutputStream body = new ByteArrayOutputStream();

    /** Текстовое поле без типа, как у curl -F: платформы читают его в UTF-8. */
    MultipartBody field(String name, String value) {
        return part("form-data; name=\"" + name + "\"", null, value.getBytes(StandardCharsets.UTF_8));
    }

    /** JSON-поле, например payload_json вебхука Discord. */
    MultipartBody json(String name, String value) {
        return part("form-data; name=\"" + name + "\"", "application/json", value.getBytes(StandardCharsets.UTF_8));
    }

    /** Файл картинки с именем, по расширению которого платформа определяет формат. */
    MultipartBody file(String name, ImageFile image) {
        return part("form-data; name=\"" + name + "\"; filename=\"" + image.filename() + "\"", image.contentType(), image.data());
    }

    /** Заголовок Content-Type с границей частей. */
    String contentType() { return "multipart/form-data; boundary=" + boundary; }

    /** Завершает тело закрывающей границей. */
    HttpRequest.BodyPublisher publisher() {
        ByteArrayOutputStream complete = new ByteArrayOutputStream();
        complete.writeBytes(body.toByteArray());
        complete.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return HttpRequest.BodyPublishers.ofByteArray(complete.toByteArray());
    }

    /** Имена полей задаёт код сервиса, поэтому экранирование в заголовках не требуется. */
    private MultipartBody part(String disposition, String type, byte[] content) {
        String headers = "--" + boundary + "\r\nContent-Disposition: " + disposition + "\r\n"
                + (type == null ? "" : "Content-Type: " + type + "\r\n") + "\r\n";
        body.writeBytes(headers.getBytes(StandardCharsets.UTF_8));
        body.writeBytes(content);
        body.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
        return this;
    }
}
