package club.ttg.findgame.publications;

/** Очищает и сокращает названия, системы и жанры для публикаций. */
final class DigestText {
    private DigestText() {}

    /** Оставляет одну безопасную строку и не разрывает суррогатную пару при сокращении. */
    static String compact(String text, int maximum) {
        return limit(plainText(text), maximum);
    }

    /** Сокращает готовый текст до предела с многоточием, не разрывая суррогатную пару. */
    static String limit(String text, int maximum) {
        if (text.length() <= maximum) return text;
        int end = maximum - 1;
        if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end).stripTrailing() + "…";
    }

    /** Убирает HTML, управляющие символы и разметку из названий. */
    private static String plainText(String text) {
        if (text == null || text.isBlank()) return "";
        return text
                .replaceAll("(?is)<(script|style)\\b[^>]*>.*?</\\1\\s*>", " ")
                .replaceAll("<[^>]*>", " ")
                .replaceAll("[\\p{Cntrl}\\p{Cf}*_`~|<>\\[\\](){}\\\\]", " ")
                .replaceAll("[\\s\\p{Z}]+", " ").trim();
    }
}
