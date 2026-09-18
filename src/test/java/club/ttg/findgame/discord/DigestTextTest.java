package club.ttg.findgame.discord;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Проверяет безопасные названия, системы и жанры в текстовой подборке. */
class DigestTextTest {
    /** Убирает разметку, скрытый HTML и переносы из публичных полей. */
    @Test void fieldsBecomeSinglePlainTextLine() {
        assertThat(DigestText.compact("**Тайна**\n<p>Город</p><script>hidden()</script> [Детектив]", 80))
                .isEqualTo("Тайна Город Детектив");
        assertThat(DigestText.compact(null, 80)).isEmpty();
        assertThat(DigestText.compact("   ", 80)).isEmpty();
    }

    /** Сокращение длинного названия сохраняет целые эмодзи и помещается в лимит. */
    @Test void compactFieldsRespectLimitAndUnicode() {
        assertThat(DigestText.compact("🎲".repeat(100), 80)).isEqualTo("🎲".repeat(39) + "…");
        assertThat(DigestText.compact("Я".repeat(80), 80)).hasSize(80).doesNotEndWith("…");
        assertThat(DigestText.compact("Я".repeat(81), 80)).isEqualTo("Я".repeat(79) + "…");
    }
}
