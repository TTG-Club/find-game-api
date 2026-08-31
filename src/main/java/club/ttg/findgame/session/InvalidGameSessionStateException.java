package club.ttg.findgame.session;

/**
 * Сессия уже в том состоянии, в которое её переводят: завершать завершённую
 * нечего.
 */
public class InvalidGameSessionStateException extends RuntimeException {

    public InvalidGameSessionStateException(String message) {
        super(message);
    }
}
