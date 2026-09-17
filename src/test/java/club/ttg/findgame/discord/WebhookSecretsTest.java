package club.ttg.findgame.discord;

import org.junit.jupiter.api.Test;
import java.util.Base64;
import static org.assertj.core.api.Assertions.*;

/** Проверяет сохранение вебхуков без дополнительного ключа и совместимость прежних записей. */
class WebhookSecretsTest {
    private static final String AUTHENTICATION_SECRET = "0123456789abcdef0123456789abcdef";
    private static final String DEDICATED_KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String WEBHOOK = "https://discord.com/api/webhooks/123456789012345678/" + "a".repeat(60);
    // Независимые фикстуры: node:crypto hkdfSync + createCipheriv(aes-256-gcm), nonce 00..0b.
    private static final String DERIVED_FIXTURE = "hkdf-v1:AAECAwQFBgcICQoLbqDY3LG/wpS5MnPcUMxQbk6WFH40FZvkOUvFj3hYJICvz8oLZAQpNfJpy34MbjU+R1hreGPY1bxQConLQCYoEPLyZlq/SEwMqe+NVZdJRE6N4nhETmUDPANyMR62jdfAbYCtUogadkh49BClYBme3oa7OgvYpPRkba1Vgza7nSg=";
    private static final String LEGACY_FIXTURE = "AAECAwQFBgcICQoL4L9HInPHoNamESGGknG6IjcDxbXe7ebZuK6GY+JaZBzDxo6WeFIHywDp/bucjno3AsOv1Ybs7L/NmDwic+vSx+FVi7LyIXvNJ7rI9JA53VYelIZhz2S1IVazzgxNv+B3wGHaQLXSf7B+xX5WhFUXW5V+xIeUrfVkp/fcMRVa0tE=";

    /** После перезапуска и на другой реплике тот же серверный секрет читает запись. */
    @Test void worksWithoutDedicatedConfigurationAcrossInstances() {
        WebhookSecrets first = new WebhookSecrets("", AUTHENTICATION_SECRET);
        WebhookSecrets restarted = new WebhookSecrets("", AUTHENTICATION_SECRET);
        String encrypted = first.encrypt(WEBHOOK);
        assertThat(first.configured()).isTrue();
        assertThat(encrypted).startsWith("hkdf-v1:").doesNotContain(WEBHOOK, AUTHENTICATION_SECRET);
        assertThat(first.encrypt(WEBHOOK)).isNotEqualTo(encrypted);
        assertThat(restarted.decrypt(encrypted)).isEqualTo(WEBHOOK);
    }

    /** Независимая реализация подтверждает формат HKDF и AES-GCM, а не только симметричность нашего кода. */
    @Test void readsIndependentHkdfAndLegacyFixtures() {
        assertThat(new WebhookSecrets("", AUTHENTICATION_SECRET).decrypt(DERIVED_FIXTURE)).isEqualTo(WEBHOOK);
        assertThat(new WebhookSecrets(DEDICATED_KEY, AUTHENTICATION_SECRET).decrypt(LEGACY_FIXTURE)).isEqualTo(WEBHOOK);
    }

    /** Добавление отдельного ключа не ломает уже сохранённые автоматически зашифрованные ссылки. */
    @Test void addingDedicatedKeyPreservesDerivedRecords() {
        WebhookSecrets upgraded = new WebhookSecrets(DEDICATED_KEY, AUTHENTICATION_SECRET);
        assertThat(upgraded.decrypt(DERIVED_FIXTURE)).isEqualTo(WEBHOOK);
        String dedicatedRecord = upgraded.encrypt(WEBHOOK);
        assertThat(dedicatedRecord).doesNotStartWith("hkdf-v1:");
        assertThat(new WebhookSecrets(DEDICATED_KEY, "changed-authentication-secret-at-least-32-bytes")
                .decrypt(dedicatedRecord)).isEqualTo(WEBHOOK);
    }

    /** Другой серверный секрет и повреждённые данные не раскрывают вебхук. */
    @Test void failsClosedForWrongKeyCorruptEnvelopeAndMissingLegacyKey() {
        WebhookSecrets changed = new WebhookSecrets("", "changed-authentication-secret-at-least-32-bytes");
        assertThatThrownBy(() -> changed.decrypt(DERIVED_FIXTURE)).isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(WEBHOOK);
        WebhookSecrets secrets = new WebhookSecrets("", AUTHENTICATION_SECRET);
        assertThatThrownBy(() -> secrets.decrypt(LEGACY_FIXTURE)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> secrets.decrypt(DERIVED_FIXTURE.substring(0, DERIVED_FIXTURE.length() - 5))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new WebhookSecrets("", "short")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new WebhookSecrets("invalid-key", AUTHENTICATION_SECRET)).isInstanceOf(IllegalStateException.class);
    }
}
