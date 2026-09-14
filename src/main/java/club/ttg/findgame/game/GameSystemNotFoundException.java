package club.ttg.findgame.game;

public class GameSystemNotFoundException extends RuntimeException {

    public GameSystemNotFoundException(String code) {
        super("Игровая система не найдена: " + code);
    }
}
