package club.ttg.findgame.review;

/**
 * Оценку уже не поправить: она раскрыта второй стороне.
 *
 * Отдельно от прочих отказов: клиенту важно не «нельзя оценивать», а «своё
 * слово уже сказано».
 */
public class ReviewLockedException extends RuntimeException {

    public ReviewLockedException() {
        super("Оценка уже раскрыта, изменить её нельзя");
    }
}
