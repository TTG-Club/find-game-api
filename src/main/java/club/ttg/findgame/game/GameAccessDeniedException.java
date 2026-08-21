package club.ttg.findgame.game;

public class GameAccessDeniedException extends RuntimeException {

    public GameAccessDeniedException() {
        super("Только мастер-владелец может завершить игру");
    }
}
