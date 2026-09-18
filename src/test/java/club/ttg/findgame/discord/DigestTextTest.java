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
        assertThat(DigestText.description(description, mapper, 600)).isEqualTo("Тайна Город & магия Подробнее.");
        assertThat(DigestText.description("[Город] ищет героев", mapper, 600)).isEqualTo("Город ищет героев.");
    }

    /** Вложенные маркеры сохраняют видимый текст, скрывая типы и атрибуты. */
    @Test void nestedMarkupKeepsVisibleText() {
        assertThat(DigestText.description("{@b Тайна {@i старого} города} {@link Подробности | href:\"https://example.org\"}", mapper, 180))
                .isEqualTo("Тайна старого города Подробности.");
        assertThat(DigestText.description("{@list {@li Найдите мага}{@li Раскройте тайну}}{@br}Начало в таверне", mapper, 180))
                .isEqualTo("Найдите мага Раскройте тайну Начало в таверне.");
    }

    /** JSON редактора читается только через текст и содержимое узлов. */
    @Test void editorJsonNeverPublishesAttributes() {
        String description = """
                ["Начало", {"type":"list","attrs":{"style":"hidden"},"content":[
                  {"type":"text","text":"Найдите мага"},
                  {"type":"link","attrs":{"href":"https://example.org"},"content":["в городе"]}
                ]}, {"type":"image","attrs":{"src":"secret.png"}}]
                """;
        assertThat(DigestText.description(description, mapper, 180)).isEqualTo("Начало Найдите мага в городе.");
        assertThat(DigestText.description("[]", mapper, 180)).isEmpty();
        assertThat(DigestText.description(null, mapper, 180)).isEmpty();
        assertThat(DigestText.description("   ", mapper, 180)).isEmpty();
    }

    /** Длинные незаконченные фразы пропускаются; компактные названия не разрывают эмодзи. */
    @Test void longDescriptionsRespectLimitAndUnicode() {
        String excerpt = DigestText.description("Я".repeat(178) + "🎲" + "а".repeat(19820), mapper, 180);
        assertThat(excerpt).isEmpty();
        assertThat(DigestText.description("Я".repeat(179), mapper, 180)).isEqualTo("Я".repeat(179) + ".");
        assertThat(DigestText.description("Я".repeat(180), mapper, 180)).isEmpty();
        assertThat(DigestText.compact("🎲".repeat(100), 80)).isEqualTo("🎲".repeat(39) + "…");
    }

    /** Сохраняет законченные предложения и не добавляет обрывок следующего. */
    @Test void excerptEndsAtSentenceBoundary() {
        assertThat(DigestText.description("Место, пропитанное печалью и злобой, призвало вас. Вам предстоит узнать, что случилось. Если победите чудовищ," + " долго".repeat(50), mapper, 600))
                .isEqualTo("Место, пропитанное печалью и злобой, призвало вас. Вам предстоит узнать, что случилось…");
        assertThat(DigestText.description("Кто скрывается в городе? Найдите его! Незаконченная фраза", mapper, 600))
                .isEqualTo("Кто скрывается в городе? Найдите его!…");
        assertThat(DigestText.description("Найдите мага. Иначе...", mapper, 600)).isEqualTo("Найдите мага…");
        assertThat(DigestText.description("Продолжение следует…", mapper, 600)).isEmpty();
    }

    /** При отсутствии короткого предложения берёт первое целиком в пределах жёсткого лимита. */
    @Test void longFirstSentenceIsKeptWholeOrOmitted() {
        String first = "Героям предстоит " + "расследовать тайны ".repeat(15) + "города.";
        assertThat(DigestText.description(first + " Затем начнётся новое приключение.", mapper, 600))
                .isEqualTo(first.substring(0, first.length() - 1) + "…");
        assertThat(DigestText.description("Расследуйте " + "тайны ".repeat(110) + "города.", mapper, 600)).isEmpty();
    }

    /** Точка в версии игры, сокращении или инициалах не считается концом предложения. */
    @Test void abbreviationsAndQuotesStayInsideSentences() {
        String first = "Игра D&D 5.5 проходит в г. " + "ОченьДалёком".repeat(16) + " городе.";
        assertThat(DigestText.description(first + " Ждём вас.", mapper, 600))
                .isEqualTo(first.substring(0, first.length() - 1) + "…");
        assertThat(DigestText.description("Он сказал: «Найдите А. С. Пушкина». Дальше " + "тайны ".repeat(90), mapper, 600))
                .isEqualTo("Он сказал: «Найдите А. С. Пушкина»…");
    }

    /** Однобуквенное слово и сокращение в конце полного текста не стирают описание. */
    @Test void completeSentencesAreNotMistakenForInitials() {
        assertThat(DigestText.description("Вы и я. Впереди " + "приключения ".repeat(80), mapper, 600))
                .isEqualTo("Вы и я…");
        assertThat(DigestText.description("Начало в 2026 г.", mapper, 600)).isEqualTo("Начало в 2026 г.");
    }

    /** При подгонке под общий лимит многоточие учитывается в длине и не обрывает фразу. */
    @Test void descriptionFitsRemainingSpaceWithoutCuttingSentences() {
        assertThat(DigestText.fitDescription("Найдите мага. Дальше приключения…", 13)).isEqualTo("Найдите мага…");
        assertThat(DigestText.fitDescription("Найдите мага. Дальше приключения…", 11)).isEmpty();
        assertThat(DigestText.fitDescription("Найдите мага…", 13)).isEqualTo("Найдите мага…");
        assertThat(DigestText.fitDescription("Найдите мага.", 13)).isEqualTo("Найдите мага.");
        assertThat(DigestText.fitDescription("Кто? Длинное продолжение", 4)).isEmpty();
        assertThat(DigestText.fitDescription("Кто? Длинное продолжение", 5)).isEqualTo("Кто?…");
        assertThat(DigestText.fitDescription("Найдите мага.", 0)).isEmpty();
    }
}
