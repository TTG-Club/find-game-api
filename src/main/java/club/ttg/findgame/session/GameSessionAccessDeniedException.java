package club.ttg.findgame.session;

public class GameSessionAccessDeniedException extends RuntimeException {

    public GameSessionAccessDeniedException() {
        super("Только мастер-владелец может создавать сессии этой игры");
    }
}
