package club.ttg.findgame.game;

public class InvalidPlayerCountException extends RuntimeException {

    public InvalidPlayerCountException() {
        super("Количество игроков для старта не может превышать максимальное количество игроков");
    }

    public InvalidPlayerCountException(String message) {
        super(message);
    }
}
