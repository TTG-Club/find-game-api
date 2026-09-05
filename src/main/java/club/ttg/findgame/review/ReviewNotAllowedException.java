package club.ttg.findgame.review;

/** Оценку ставит или читает не тот, кому она полагается. */
public class ReviewNotAllowedException extends RuntimeException {

    public ReviewNotAllowedException(String message) {
        super(message);
    }
}
