package club.ttg.findgame.game;

public class ActiveGameLimitExceededException extends RuntimeException {

    public ActiveGameLimitExceededException() {
        super("Без активной подписки можно иметь только одну незавершённую игру");
    }
}
