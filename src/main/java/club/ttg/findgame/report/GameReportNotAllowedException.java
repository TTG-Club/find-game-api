package club.ttg.findgame.report;

/** Жалоба не может быть отправлена текущим пользователем. */
public class GameReportNotAllowedException extends RuntimeException {

    public GameReportNotAllowedException(String message) {
        super(message);
    }
}
