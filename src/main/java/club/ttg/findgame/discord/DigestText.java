package club.ttg.findgame.discord;

import org.springframework.web.util.HtmlUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.regex.Pattern;

/** Извлекает публичный текст редактора без атрибутов, ссылок и служебной разметки. */
final class DigestText {
    private static final int MAX_DEPTH = 32;
    private static final Pattern MARKER = Pattern.compile("\\{@[\\p{L}\\p{N}_-]+(?:\\s+([^{}|]*))?(?:\\|[^{}]*)?}");

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
        return compact(HtmlUtils.htmlUnescape(source), maximum);
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
        if (text == null || text.isBlank()) return "";
        String cleaned = text
                .replaceAll("(?is)<(script|style)\\b[^>]*>.*?</\\1\\s*>", " ")
                .replaceAll("<[^>]*>", " ")
                .replaceAll("[\\p{Cntrl}\\p{Cf}*_`~|<>\\[\\](){}\\\\]", " ")
                .replaceAll("[\\s\\p{Z}]+", " ").trim();
        if (cleaned.length() <= maximum) return cleaned;
        int end = maximum - 1;
        if (Character.isHighSurrogate(cleaned.charAt(end - 1))) end--;
        return cleaned.substring(0, end).stripTrailing() + "…";
    }
}
