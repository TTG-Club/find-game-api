package club.ttg.findgame.discord;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

/** Проверяет форматы сохранённых описаний и безопасное сокращение текста. */
class DigestTextTest {
    private final ObjectMapper mapper = new ObjectMapper();

    /** Старые описания остаются читаемыми без Markdown, HTML и адресов форматированных ссылок. */
    @Test void legacyMarkdownAndHtmlBecomePlainText() {
        String description = "## **Тайна**\n<p>Город &amp; магия</p> [Подробнее](https://example.org) <script>hidden()</script>";
        assertThat(DigestText.description(description, mapper, 180)).isEqualTo("Тайна Город & магия Подробнее");
        assertThat(DigestText.description("[Город] ищет героев", mapper, 180)).isEqualTo("Город ищет героев");
    }

    /** Вложенные маркеры сохраняют видимый текст, скрывая типы и атрибуты. */
    @Test void nestedMarkupKeepsVisibleText() {
        assertThat(DigestText.description("{@b Тайна {@i старого} города} {@link Подробности | href:\"https://example.org\"}", mapper, 180))
                .isEqualTo("Тайна старого города Подробности");
        assertThat(DigestText.description("{@list {@li Найдите мага}{@li Раскройте тайну}}{@br}Начало в таверне", mapper, 180))
                .isEqualTo("Найдите мага Раскройте тайну Начало в таверне");
    }

    /** JSON редактора читается только через текст и содержимое узлов. */
    @Test void editorJsonNeverPublishesAttributes() {
        String description = """
                ["Начало", {"type":"list","attrs":{"style":"hidden"},"content":[
                  {"type":"text","text":"Найдите мага"},
                  {"type":"link","attrs":{"href":"https://example.org"},"content":["в городе"]}
                ]}, {"type":"image","attrs":{"src":"secret.png"}}]
                """;
        assertThat(DigestText.description(description, mapper, 180)).isEqualTo("Начало Найдите мага в городе");
        assertThat(DigestText.description("[]", mapper, 180)).isEmpty();
        assertThat(DigestText.description(null, mapper, 180)).isEmpty();
        assertThat(DigestText.description("   ", mapper, 180)).isEmpty();
    }

    /** Сокращение отмечено многоточием и не оставляет половину эмодзи. */
    @Test void longDescriptionsRespectLimitAndUnicode() {
        String excerpt = DigestText.description("Я".repeat(178) + "🎲" + "а".repeat(19820), mapper, 180);
        assertThat(excerpt).isEqualTo("Я".repeat(178) + "…").hasSizeLessThanOrEqualTo(180);
        assertThat(DigestText.description("Я".repeat(180), mapper, 180)).isEqualTo("Я".repeat(180));
        assertThat(DigestText.compact("🎲".repeat(100), 80)).isEqualTo("🎲".repeat(39) + "…");
    }
}
