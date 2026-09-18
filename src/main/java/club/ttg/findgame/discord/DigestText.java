package club.ttg.findgame.discord;

/** Очищает и сокращает названия, системы и жанры для обычного текста Discord. */
final class DigestText {
    private DigestText() {}

    /** Оставляет одну безопасную строку и не разрывает суррогатную пару при сокращении. */
    static String compact(String text, int maximum) {
        String cleaned = plainText(text);
        if (cleaned.length() <= maximum) return cleaned;
        int end = maximum - 1;
        if (Character.isHighSurrogate(cleaned.charAt(end - 1))) end--;
        return cleaned.substring(0, end).stripTrailing() + "…";
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
