package club.ttg.findgame.review;

import java.time.Instant;

/**
 * Окно на оценку закрылось.
 *
 * Отдельно от прочих отказов: клиенту важно не «нельзя», а «было можно до».
 */
public class ReviewWindowClosedException extends RuntimeException {

    private final Instant closedAt;

    public ReviewWindowClosedException(Instant closedAt) {
        super("Оценить встречу можно было до " + closedAt);
        this.closedAt = closedAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }
}
