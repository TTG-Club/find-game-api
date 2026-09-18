package club.ttg.findgame.discord;

import org.springframework.web.util.HtmlUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.text.BreakIterator;
import java.util.Locale;
import java.util.regex.Pattern;

/** Извлекает публичный текст редактора без атрибутов и служебной разметки. */
final class DigestText {
    private static final int MAX_DEPTH = 32;
    private static final int PREFERRED_DESCRIPTION_LENGTH = 180;
    private static final Pattern MARKER = Pattern.compile("\\{@[\\p{L}\\p{N}_-]+(?:\\s+([^{}|]*))?(?:\\|[^{}]*)?}");
    private static final Pattern SENTENCE_END = Pattern.compile("[.!?][»”\"']*$");
    private static final Pattern ELLIPSIS_END = Pattern.compile("(?:\\.{2,}|…)[»”\"']*$");
    private static final Pattern INITIAL = Pattern.compile("(?:^|\\s)\\p{Lu}\\.$");
    private static final Pattern ABBREVIATION = Pattern.compile("(?:^|\\s)(?:г|т|д|п|с|ч|гг|ул|им|стр|рис|проф|акад|пер|пр|руб|коп)\\.$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private DigestText() {}

    /** Разворачивает JSON-узлы редактора и сокращает описание до лимита сообщения. */
    static String description(String description, ObjectMapper mapper, int maximum) {
        String source = description == null ? "" : description.trim();
        if ((source.startsWith("[") || source.startsWith("{")) && !source.startsWith("{@")) {
            try {
                JsonNode root = mapper.readTree(source);
                StringBuilder visible = new StringBuilder();
                appendText(root, visible, 0);
                source = visible.toString();
            } catch (RuntimeException exception) {
                // Старые описания могут начинаться со скобки, не являясь JSON.
            }
        }
        for (int depth = 0; depth < MAX_DEPTH; depth++) {
            String unwrapped = MARKER.matcher(source).replaceAll("$1 ");
            if (unwrapped.equals(source)) break;
            source = unwrapped;
        }
        source = source.replaceAll("!?\\[([^\\]\\r\\n]*)]\\([^\\r\\n)]*\\)", "$1")
                .replaceAll("(?m)^\\s{0,3}#{1,6}\\s+", "");
        return sentences(plainText(HtmlUtils.htmlUnescape(source)), maximum);
    }

    /** Выбирает целые предложения; длинное первое допускается до жёсткого лимита. */
    private static String sentences(String text, int maximum) {
        if (text.isBlank() || maximum < 2) return "";
        BreakIterator boundaries = BreakIterator.getSentenceInstance(Locale.forLanguageTag("ru"));
        boundaries.setText(text);
        String excerpt = "";
        for (int end = boundaries.next(); end != BreakIterator.DONE; end = boundaries.next()) {
            String candidate = text.substring(0, end).stripTrailing();
            String shortened = markExcerpt(candidate, text);
            if (shortened.length() > maximum) break;
            if (!SENTENCE_END.matcher(candidate).find() || ELLIPSIS_END.matcher(candidate).find()) continue;
            if (end < text.length() && (ABBREVIATION.matcher(candidate).find()
                    || (INITIAL.matcher(candidate).find() && Character.isUpperCase(text.codePointAt(end))))) continue;
            if (shortened.length() > PREFERRED_DESCRIPTION_LENGTH) return excerpt.isEmpty() ? shortened : excerpt;
            excerpt = shortened;
        }
        if (!excerpt.isEmpty()) return excerpt;
        // Короткий текст без пунктуации сохраняем целиком, длинный не обрываем искусственной точкой.
        if (text.length() >= Math.min(PREFERRED_DESCRIPTION_LENGTH, maximum)
                || ELLIPSIS_END.matcher(text).find()) return "";
        return text + ".";
    }

    /** Сокращает уже очищенное описание под оставшееся место, сохраняя целые предложения. */
    static String fitDescription(String text, int maximum) {
        return text.length() <= maximum ? text : sentences(text, maximum);
    }

    /** Многоточие показывает, что на сайте есть продолжение; полный текст не меняется. */
    private static String markExcerpt(String excerpt, String source) {
        if (excerpt.length() == source.length()) return excerpt;
        return (excerpt.endsWith(".") ? excerpt.substring(0, excerpt.length() - 1) : excerpt) + "…";
    }

    /** Читает только отображаемые поля AST; адреса и прочие атрибуты игнорирует. */
    private static void appendText(JsonNode node, StringBuilder visible, int depth) {
        if (node == null || depth > MAX_DEPTH) return;
        if (node.isString()) {
            visible.append(node.stringValue()).append(' ');
        } else if (node.isArray()) {
            for (JsonNode child : node) appendText(child, visible, depth + 1);
        } else if (node.isObject()) {
            JsonNode text = node.get("text");
            if (text != null && text.isString()) appendText(text, visible, depth + 1);
            else appendText(node.get("content"), visible, depth + 1);
        }
    }

    /** Оставляет одну безопасную строку и не разрывает суррогатную пару при сокращении. */
    static String compact(String text, int maximum) {
        String cleaned = plainText(text);
        if (cleaned.length() <= maximum) return cleaned;
        int end = maximum - 1;
        if (Character.isHighSurrogate(cleaned.charAt(end - 1))) end--;
        return cleaned.substring(0, end).stripTrailing() + "…";
    }

    /** Убирает форматирование без сокращения, чтобы затем найти настоящие границы предложений. */
    private static String plainText(String text) {
        if (text == null || text.isBlank()) return "";
        return text
                .replaceAll("(?is)<(script|style)\\b[^>]*>.*?</\\1\\s*>", " ")
                .replaceAll("<[^>]*>", " ")
                .replaceAll("[\\p{Cntrl}\\p{Cf}*_`~|<>\\[\\](){}\\\\]", " ")
                .replaceAll("[\\s\\p{Z}]+", " ").trim();
    }
}
