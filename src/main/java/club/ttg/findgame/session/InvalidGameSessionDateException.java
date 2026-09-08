package club.ttg.findgame.session;

/**
 * Некорректная дата начала сессии или расписание серии.
 */
public class InvalidGameSessionDateException extends RuntimeException {

    public InvalidGameSessionDateException(String message) {
        super(message);
    }
}
