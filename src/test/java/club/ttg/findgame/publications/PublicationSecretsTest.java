package club.ttg.findgame.publications;

import org.junit.jupiter.api.Test;
import static club.ttg.findgame.publications.PublicationModels.Platform;
import java.util.Base64;
import static org.assertj.core.api.Assertions.*;

/** Проверяет сохранение вебхуков без дополнительного ключа и совместимость прежних записей. */
class PublicationSecretsTest {
    private static final String AUTHENTICATION_SECRET = "0123456789abcdef0123456789abcdef";
    private static final String DEDICATED_KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String WEBHOOK = "https://discord.com/api/webhooks/123456789012345678/" + "a".repeat(60);

    /** ID чата нельзя восстановить из отпечатка без ключа; шифротекст связан с платформой. */
    @Test void telegramIdsUseSeparateEncryptionAndKeyedFingerprints() {
        PublicationSecrets secrets = new PublicationSecrets("", AUTHENTICATION_SECRET);
        PublicationSecrets restarted = new PublicationSecrets(DEDICATED_KEY, AUTHENTICATION_SECRET);
        String chatId = "-1001234567890";
        String encrypted = secrets.encrypt(Platform.TELEGRAM, chatId);
        assertThat(encrypted).doesNotContain(chatId);
        assertThat(secrets.encrypt(Platform.TELEGRAM, chatId)).isNotEqualTo(encrypted);
        assertThat(restarted.decrypt(Platform.TELEGRAM, encrypted)).isEqualTo(chatId);
        assertThat(restarted.fingerprint(Platform.TELEGRAM, chatId)).isEqualTo(secrets.fingerprint(Platform.TELEGRAM, chatId));
        PublicationSecrets changed = new PublicationSecrets("", "changed-authentication-secret-at-least-32-bytes");
        assertThat(changed.fingerprint(Platform.TELEGRAM, chatId)).isNotEqualTo(secrets.fingerprint(Platform.TELEGRAM, chatId));
        assertThatThrownBy(() -> changed.decrypt(Platform.TELEGRAM, encrypted)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> secrets.decrypt(Platform.DISCORD, encrypted)).isInstanceOf(IllegalStateException.class);
        for (String invalid : java.util.List.of("0", "123456", "-0", "-001", "@channel", "-4503599627370496", "https://example.com", "-1001/path")) {
            assertThatThrownBy(() -> secrets.normalize(Platform.TELEGRAM, invalid)).isInstanceOf(RuntimeException.class);
        }
        assertThat(secrets.normalize(Platform.TELEGRAM, "  " + chatId + "  ")).isEqualTo(chatId);
    }
    // Независимые фикстуры: node:crypto hkdfSync + createCipheriv(aes-256-gcm), nonce 00..0b.
    private static final String DERIVED_FIXTURE = "hkdf-v1:AAECAwQFBgcICQoLbqDY3LG/wpS5MnPcUMxQbk6WFH40FZvkOUvFj3hYJICvz8oLZAQpNfJpy34MbjU+R1hreGPY1bxQConLQCYoEPLyZlq/SEwMqe+NVZdJRE6N4nhETmUDPANyMR62jdfAbYCtUogadkh49BClYBme3oa7OgvYpPRkba1Vgza7nSg=";
    private static final String LEGACY_FIXTURE = "AAECAwQFBgcICQoL4L9HInPHoNamESGGknG6IjcDxbXe7ebZuK6GY+JaZBzDxo6WeFIHywDp/bucjno3AsOv1Ybs7L/NmDwic+vSx+FVi7LyIXvNJ7rI9JA53VYelIZhz2S1IVazzgxNv+B3wGHaQLXSf7B+xX5WhFUXW5V+xIeUrfVkp/fcMRVa0tE=";

    /** После перезапуска и на другой реплике тот же серверный секрет читает запись. */
    @Test void worksWithoutDedicatedConfigurationAcrossInstances() {
        PublicationSecrets first = new PublicationSecrets("", AUTHENTICATION_SECRET);
        PublicationSecrets restarted = new PublicationSecrets("", AUTHENTICATION_SECRET);
        String encrypted = first.encrypt(Platform.DISCORD, WEBHOOK);
        assertThat(first.configured()).isTrue();
        assertThat(encrypted).startsWith("hkdf-v1:").doesNotContain(WEBHOOK, AUTHENTICATION_SECRET);
        assertThat(first.encrypt(Platform.DISCORD, WEBHOOK)).isNotEqualTo(encrypted);
        assertThat(restarted.decrypt(Platform.DISCORD, encrypted)).isEqualTo(WEBHOOK);
    }

    /** Независимая реализация подтверждает формат HKDF и AES-GCM, а не только симметричность нашего кода. */
    @Test void readsIndependentHkdfAndLegacyFixtures() {
        assertThat(new PublicationSecrets("", AUTHENTICATION_SECRET).decrypt(Platform.DISCORD, DERIVED_FIXTURE)).isEqualTo(WEBHOOK);
        assertThat(new PublicationSecrets(DEDICATED_KEY, AUTHENTICATION_SECRET).decrypt(Platform.DISCORD, LEGACY_FIXTURE)).isEqualTo(WEBHOOK);
    }

    /** Добавление отдельного ключа не ломает уже сохранённые автоматически зашифрованные ссылки. */
    @Test void addingDedicatedKeyPreservesDerivedRecords() {
        PublicationSecrets upgraded = new PublicationSecrets(DEDICATED_KEY, AUTHENTICATION_SECRET);
        assertThat(upgraded.decrypt(Platform.DISCORD, DERIVED_FIXTURE)).isEqualTo(WEBHOOK);
        String dedicatedRecord = upgraded.encrypt(Platform.DISCORD, WEBHOOK);
        assertThat(dedicatedRecord).doesNotStartWith("hkdf-v1:");
        assertThat(new PublicationSecrets(DEDICATED_KEY, "changed-authentication-secret-at-least-32-bytes")
                .decrypt(Platform.DISCORD, dedicatedRecord)).isEqualTo(WEBHOOK);
    }

    /** Другой серверный секрет и повреждённые данные не раскрывают вебхук. */
    @Test void failsClosedForWrongKeyCorruptEnvelopeAndMissingLegacyKey() {
        PublicationSecrets changed = new PublicationSecrets("", "changed-authentication-secret-at-least-32-bytes");
        assertThatThrownBy(() -> changed.decrypt(Platform.DISCORD, DERIVED_FIXTURE)).isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(WEBHOOK);
        PublicationSecrets secrets = new PublicationSecrets("", AUTHENTICATION_SECRET);
        assertThatThrownBy(() -> secrets.decrypt(Platform.DISCORD, LEGACY_FIXTURE)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> secrets.decrypt(Platform.DISCORD, DERIVED_FIXTURE.substring(0, DERIVED_FIXTURE.length() - 5))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new PublicationSecrets("", "short")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new PublicationSecrets("invalid-key", AUTHENTICATION_SECRET)).isInstanceOf(IllegalStateException.class);
    }
}
