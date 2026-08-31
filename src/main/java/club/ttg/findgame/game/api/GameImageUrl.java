package club.ttg.findgame.game.api;

/**
 * Ограничение обложки игры.
 *
 * Картинки сайта лежат в его же хранилище, и загрузчик отдаёт относительный
 * путь {@code /s3/<ключ>} — так их хранят и остальные разделы. Проверка на
 * абсолютный URL такой путь отвергала, поэтому здесь допустимы оба вида:
 * путь от корня сайта и внешняя ссылка, если мастер вставил чужую картинку.
 */
public final class GameImageUrl {

    public static final String PATTERN = "^(https?://\\S+|/\\S+)$";

    public static final String MESSAGE =
            "должно быть ссылкой http(s) или путём от корня сайта, например /s3/games/cover.webp";

    private GameImageUrl() {
    }
}
