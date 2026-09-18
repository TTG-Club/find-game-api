package club.ttg.findgame.publications;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;
import static club.ttg.findgame.publications.PublicationModels.Platform;

/** Шифрует адреса каналов; ключи хранятся вне базы и не возвращаются в API. */
@Component
public class PublicationSecrets {
    private static final Pattern URL = Pattern.compile("https://discord\\.com/api(?:/v10)?/webhooks/[0-9]{17,20}/[A-Za-z0-9_-]{40,200}");
    private static final String DERIVED_PREFIX = "hkdf-v1:";
    private static final String KEY_CONTEXT = "ttg.find-game.discord.webhook-encryption.v1";
    private final SecretKeySpec dedicatedKey;
    private final SecretKeySpec derivedKey;
    private final SecretKeySpec telegramKey;
    private final SecretKeySpec telegramFingerprintKey;
    private static final String TELEGRAM_PREFIX = "telegram-v1:";
    private static final Pattern CHAT_ID = Pattern.compile("-[1-9][0-9]{0,15}");
    private final SecureRandom random = new SecureRandom();

    /** Использует секрет действующей авторизации; отдельный ключ остаётся необязательным. */
    public PublicationSecrets(@Value("${discord-publications.encryption-key:}") String encodedKey,
                          @Value("${auth-service.jwt-secret}") String authenticationSecret) {
        derivedKey = deriveKey(authenticationSecret);
        dedicatedKey = encodedKey.isBlank() ? null : decodeDedicatedKey(encodedKey);
        // Ключ Discord не меняет шифрование и отпечатки Telegram при последующей настройке вебхуков.
        byte[] source = authenticationSecret.getBytes(StandardCharsets.UTF_8);
        telegramKey = deriveKey(source, "ttg.find-game.telegram.chat-encryption.v1");
        telegramFingerprintKey = deriveKey(source, "ttg.find-game.telegram.chat-fingerprint.v1");
    }

    /** Читает ранее поддерживаемый отдельный ключ без изменения формата сохранённых вебхуков. */
    private static SecretKeySpec decodeDedicatedKey(String encodedKey) {
        try {
            byte[] decoded = Base64.getDecoder().decode(encodedKey);
            if (decoded.length != 32) throw new IllegalArgumentException();
            return new SecretKeySpec(decoded, "AES");
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("DISCORD_WEBHOOK_ENCRYPTION_KEY должен содержать 32 байта в Base64");
        }
    }

    /** Получает отдельный AES-ключ через HKDF-SHA256 (RFC 5869), не используя JWT-ключ напрямую. */
    private static SecretKeySpec deriveKey(String authenticationSecret) {
        byte[] source = authenticationSecret.getBytes(StandardCharsets.UTF_8);
        if (source.length < 32) {
            throw new IllegalStateException("Серверный секрет авторизации должен содержать не менее 32 байт");
        }
        return deriveKey(source, KEY_CONTEXT);
    }

    /** Разделяет ключи шифрования и отпечатков по назначению через HKDF. */
    private static SecretKeySpec deriveKey(byte[] source, String context) {
        try {
            Mac derivation = Mac.getInstance("HmacSHA256");
            // HKDF-Extract без отдельной соли: RFC 5869 определяет 32 нулевых байта.
            derivation.init(new SecretKeySpec(new byte[32], "HmacSHA256"));
            byte[] extracted = derivation.doFinal(source);
            derivation.init(new SecretKeySpec(extracted, "HmacSHA256"));
            derivation.update(context.getBytes(StandardCharsets.UTF_8));
            // AES-256 требует один блок HKDF-Expand: info || 0x01.
            return new SecretKeySpec(derivation.doFinal(new byte[]{1}), "AES");
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Не удалось подготовить шифрование адресов каналов");
        }
    }

    /** Показывает готовность сервера без раскрытия ключа. */
    public boolean configured() { return derivedKey != null; }

    /** Разрешает числовой ID Telegram или обычный Discord-вебхук без произвольного адреса. */
    public String normalize(Platform platform, String address) {
        if (platform == Platform.TELEGRAM) {
            String chatId = address == null ? "" : address.trim();
            if (!CHAT_ID.matcher(chatId).matches() || Long.parseLong(chatId) < -4503599627370495L) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Нужен числовой ID группы или канала Telegram, начинающийся с минуса");
            }
            return chatId;
        }
        if (address == null || !URL.matcher(address.trim()).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Нужна ссылка на вебхук текстового канала discord.com");
        }
        return address.trim().replace("/api/v10/", "/api/");
    }

    /** Создаёт отпечаток для запрета повторной настройки одного канала. */
    public String fingerprint(Platform platform, String address) {
        try {
            if (platform == Platform.TELEGRAM) {
                Mac fingerprint = Mac.getInstance("HmacSHA256");
                fingerprint.init(new SecretKeySpec(telegramFingerprintKey.getEncoded(), "HmacSHA256"));
                return HexFormat.of().formatHex(fingerprint.doFinal(address.getBytes(StandardCharsets.UTF_8)));
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(address.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) { throw new IllegalStateException("Не удалось подготовить отпечаток канала"); }
    }

    /** Шифрует секрет с новым случайным nonce на каждое сохранение. */
    public String encrypt(Platform platform, String address) {
        try {
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            SecretKeySpec encryptionKey = platform == Platform.TELEGRAM ? telegramKey : dedicatedKey == null ? derivedKey : dedicatedKey;
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, new GCMParameterSpec(128, nonce));
            byte[] encrypted = cipher.doFinal(address.getBytes(StandardCharsets.UTF_8));
            String envelope = Base64.getEncoder().encodeToString(ByteBuffer.allocate(nonce.length + encrypted.length)
                    .put(nonce).put(encrypted).array());
            if (platform == Platform.TELEGRAM) return TELEGRAM_PREFIX + envelope;
            return dedicatedKey == null ? DERIVED_PREFIX + envelope : envelope;
        } catch (GeneralSecurityException exception) { throw new IllegalStateException("Не удалось зашифровать адрес канала"); }
    }

    /** Расшифровывает секрет только непосредственно перед отправкой. */
    public String decrypt(Platform platform, String secret) {
        try {
            boolean telegram = platform == Platform.TELEGRAM;
            if (telegram != secret.startsWith(TELEGRAM_PREFIX)) throw new IllegalStateException();
            boolean derived = secret.startsWith(DERIVED_PREFIX);
            SecretKeySpec decryptionKey = telegram ? telegramKey : derived ? derivedKey : dedicatedKey;
            if (decryptionKey == null) throw new IllegalStateException();
            String envelope = telegram ? secret.substring(TELEGRAM_PREFIX.length()) : derived ? secret.substring(DERIVED_PREFIX.length()) : secret;
            ByteBuffer buffer = ByteBuffer.wrap(Base64.getDecoder().decode(envelope));
            byte[] nonce = new byte[12];
            buffer.get(nonce);
            byte[] encrypted = new byte[buffer.remaining()];
            buffer.get(encrypted);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, decryptionKey, new GCMParameterSpec(128, nonce));
            return normalize(platform, new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8));
        } catch (GeneralSecurityException | RuntimeException exception) {
            throw new IllegalStateException("Не удалось расшифровать адрес канала; проверьте ключ сервера");
        }
    }
}
