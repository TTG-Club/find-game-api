package club.ttg.findgame.publications;

import com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import static club.ttg.findgame.publications.PublicationModels.*;

/** Скачивает картинку канала с сайта и приводит её к JPEG или PNG, которые принимают все платформы. */
@Component
public class PublicationImages {
    /** Путь, который выдаёт загрузка картинок сайта: /s3/раздел/владелец/имя.расширение, без «..» и параметров. */
    private static final Pattern PATH = Pattern.compile("/s3/(?:[A-Za-z0-9_-]+/){1,4}[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9]{1,10})?");
    private static final int MAX_PATH_LENGTH = 512;
    /** Лимит Telegram и Discord на фото — 10 МБ; сайт отдаёт сжатые картинки до 1 МБ. */
    private static final int MAX_BYTES = 10 * 1024 * 1024;
    /** Лимиты фото Telegram (у ВКонтакте мягче): сумма сторон и соотношение сторон. */
    private static final int MAX_SIDES = 10_000;
    private static final int MAX_RATIO = 20;
    /** Ограничивает память на раскодирование WebP. */
    private static final long MAX_PIXELS = 4096L * 4096L;
    private static final float JPEG_QUALITY = 0.9f;
    /** Каналы с общим графиком выходят одновременно: одна загрузка на всех и без лимита запросов сайта. */
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);
    private static final int CACHE_SIZE = 8;
    private final String siteUrl;
    private final HttpClient client;
    private final Map<String, Cached> cache = new LinkedHashMap<>(CACHE_SIZE, 0.75f, true);

    /** Берёт картинки с того же сайта, на который ведут ссылки подборки. */
    @Autowired
    public PublicationImages(@Value("${discord-publications.site-url}") String siteUrl) {
        this(siteUrl, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build());
    }

    /** Позволяет проверить загрузку без обращения к сайту. */
    PublicationImages(String siteUrl, HttpClient client) {
        this.siteUrl = "https://" + URI.create(siteUrl).getHost();
        this.client = client;
    }

    /** Принимает только путь из загрузки сайта: сервис не скачивает картинки с произвольных адресов. */
    static String normalize(String path) {
        String trimmed = path.trim();
        if (trimmed.length() > MAX_PATH_LENGTH || !PATH.matcher(trimmed).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Картинку нужно загрузить через админку сайта");
        }
        return trimmed;
    }

    /** Возвращает готовую к отправке картинку; причина недоступности безопасна для журнала. */
    ImageFile load(String path) throws Unavailable {
        Instant now = Instant.now();
        synchronized (cache) {
            Cached cached = cache.get(path);
            if (cached != null && cached.expiresAt().isAfter(now)) return cached.image();
        }
        ImageFile image = prepare(download(path));
        synchronized (cache) {
            cache.put(path, new Cached(image, now.plus(CACHE_TTL)));
            if (cache.size() > CACHE_SIZE) cache.remove(cache.keySet().iterator().next());
        }
        return image;
    }

    /** Освобождает соединения при завершении работы приложения. */
    @PreDestroy
    public void shutdown() { client.shutdown(); }

    /** Читает не больше лимита и не идёт по перенаправлениям. */
    private byte[] download(String path) throws Unavailable {
        HttpRequest request = HttpRequest.newBuilder(URI.create(siteUrl + normalize(path)))
                .timeout(Duration.ofSeconds(15)).header("Accept", "image/*").GET().build();
        try {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() != 200) {
                    throw new Unavailable("сайт не отдал картинку (HTTP " + response.statusCode() + ")");
                }
                byte[] data = body.readNBytes(MAX_BYTES + 1);
                if (data.length > MAX_BYTES) throw new Unavailable("картинка больше 10 МБ");
                return data;
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new Unavailable("загрузка картинки прервана");
        } catch (IOException | RuntimeException exception) {
            throw new Unavailable("не удалось скачать картинку с сайта");
        }
    }

    /** JPEG и PNG уходят как есть; WebP перекодируется: ВКонтакте его не принимает. */
    static ImageFile prepare(byte[] data) throws Unavailable {
        String format = format(data);
        if (format == null) throw new Unavailable("файл не является картинкой JPEG, PNG или WebP");
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            ImageReader reader = format.equals("webp") ? new WebPImageReaderSpi().createReaderInstance()
                    : ImageIO.getImageReadersByFormatName(format).next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1 || height < 1 || width + height > MAX_SIDES || (long) width * height > MAX_PIXELS
                        || Math.max(width, height) > MAX_RATIO * Math.min(width, height)) {
                    throw new Unavailable("размер картинки не подходит для публикации (" + width + "×" + height + ")");
                }
                if (!format.equals("webp")) return new ImageFile(data, "image/" + format);
                return encode(reader.read(0));
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException exception) {
            throw new Unavailable("не удалось прочитать картинку");
        }
    }

    /** Прозрачная картинка сохраняется в PNG, остальные — в JPEG, как core-api для обложек новостей. */
    private static ImageFile encode(BufferedImage source) throws IOException {
        int width = source.getWidth();
        int height = source.getHeight();
        int[] pixels = source.getRGB(0, 0, width, height, null, 0, width);
        boolean transparent = false;
        for (int pixel : pixels) {
            if (pixel >>> 24 != 0xFF) { transparent = true; break; }
        }
        BufferedImage target = new BufferedImage(width, height, transparent ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        target.setRGB(0, 0, width, height, pixels, 0, width);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (transparent) {
            ImageIO.write(target, "png", output);
            return new ImageFile(output.toByteArray(), "image/png");
        }
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(output)) {
            ImageWriteParam parameters = writer.getDefaultWriteParam();
            parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            parameters.setCompressionQuality(JPEG_QUALITY);
            writer.setOutput(stream);
            writer.write(null, new IIOImage(target, null, null), parameters);
        } finally {
            writer.dispose();
        }
        return new ImageFile(output.toByteArray(), "image/jpeg");
    }

    /** Определяет формат по сигнатуре файла, а не по заголовкам ответа. */
    private static String format(byte[] data) {
        if (data.length > 3 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8 && (data[2] & 0xFF) == 0xFF) return "jpeg";
        if (data.length > 8 && (data[0] & 0xFF) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G') return "png";
        if (data.length > 12 && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P') return "webp";
        return null;
    }

    /** Картинка недоступна; сообщение не содержит адресов и пригодно для журнала. */
    static final class Unavailable extends Exception {
        Unavailable(String message) { super(message, null, false, false); }
    }

    /** Подготовленная картинка и момент, после которого её нужно скачать заново. */
    private record Cached(ImageFile image, Instant expiresAt) {}
}
