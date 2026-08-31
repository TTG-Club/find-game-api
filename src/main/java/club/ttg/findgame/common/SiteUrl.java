package club.ttg.findgame.common;

/**
 * Ограничение для ссылок, которые приходят из интерфейса сайта.
 *
 * Обложки игр и листы персонажей лежат в хранилище самого сайта, и его
 * интерфейс отдаёт относительный путь — {@code /s3/<ключ>} или
 * {@code /tools/character-sheet/shared/<токен>}. Проверка на абсолютный URL
 * такие пути отвергала, поэтому здесь допустимы оба вида: путь от корня сайта
 * и внешняя ссылка, если игрок или мастер вставил чужой адрес.
 */
public final class SiteUrl {

    public static final String PATTERN = "^(https?://\\S+|/\\S+)$";

    public static final String MESSAGE =
            "должно быть ссылкой http(s) или путём от корня сайта, например /s3/games/cover.webp";

    private SiteUrl() {
    }
}
