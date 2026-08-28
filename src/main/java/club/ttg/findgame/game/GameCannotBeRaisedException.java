package club.ttg.findgame.game;

public class GameCannotBeRaisedException extends RuntimeException {

    public GameCannotBeRaisedException() {
        super("Поднять можно только открытую публичную игру");
    }
}
