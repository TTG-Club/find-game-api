package club.ttg.findgame.game;

public class GameSystemAlreadyExistsException extends RuntimeException {

    public GameSystemAlreadyExistsException(String code) {
        super("Игровая система уже существует: " + code);
    }
}
