package club.ttg.findgame.session;

/**
 * Дату сессии изменить нельзя: назначение закрывает открытую дату один раз.
 */
public class InvalidGameSessionDateException extends RuntimeException {

    public InvalidGameSessionDateException(String message) {
        super(message);
    }
}
