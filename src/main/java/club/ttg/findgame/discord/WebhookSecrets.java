package club.ttg.findgame.discord;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;

/** Шифрует вебхуки; ключ хранится вне базы и не возвращается в API. */
@Component
public class WebhookSecrets {
    private static final Pattern URL = Pattern.compile("https://discord\\.com/api(?:/v10)?/webhooks/[0-9]{17,20}/[A-Za-z0-9_-]{40,200}");
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    /** Загружает постоянный ключ AES-256; пустая настройка отключает отправку. */
    public WebhookSecrets(@Value("${discord-publications.encryption-key:}") String encodedKey) {
        if (encodedKey.isBlank()) { key = null; return; }
        try {
            byte[] decoded = Base64.getDecoder().decode(encodedKey);
            if (decoded.length != 32) throw new IllegalArgumentException();
            key = new SecretKeySpec(decoded, "AES");
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("DISCORD_WEBHOOK_ENCRYPTION_KEY должен содержать 32 байта в Base64");
        }
    }

    /** Показывает готовность сервера без раскрытия ключа. */
    public boolean configured() { return key != null; }

    /** Разрешает только обычный Discord-вебхук без произвольного адреса или параметров. */
    public String normalize(String webhookUrl) {
        if (webhookUrl == null || !URL.matcher(webhookUrl.trim()).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Нужна ссылка на вебхук текстового канала discord.com");
        }
        return webhookUrl.trim().replace("/api/v10/", "/api/");
    }

    /** Создаёт отпечаток для запрета повторной настройки одного вебхука. */
    public String fingerprint(String webhookUrl) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(webhookUrl.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    /** Шифрует секрет с новым случайным nonce на каждое сохранение. */
    public String encrypt(String webhookUrl) {
        if (!configured()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Не настроен ключ шифрования Discord");
        try {
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            byte[] encrypted = cipher.doFinal(webhookUrl.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(nonce.length + encrypted.length)
                    .put(nonce).put(encrypted).array());
        } catch (GeneralSecurityException exception) { throw new IllegalStateException("Не удалось зашифровать вебхук"); }
    }

    /** Расшифровывает секрет только непосредственно перед отправкой. */
    public String decrypt(String secret) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(Base64.getDecoder().decode(secret));
            byte[] nonce = new byte[12];
            buffer.get(nonce);
            byte[] encrypted = new byte[buffer.remaining()];
            buffer.get(encrypted);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            return normalize(new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8));
        } catch (GeneralSecurityException | RuntimeException exception) {
            throw new IllegalStateException("Не удалось расшифровать вебхук; проверьте ключ сервера");
        }
    }
}
