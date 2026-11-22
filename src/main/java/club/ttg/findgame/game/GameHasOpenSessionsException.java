package club.ttg.findgame.game;

/** Закрыть игру нельзя, пока у неё есть назначенные или идущие сессии: её можно только отменить. */
public class GameHasOpenSessionsException extends RuntimeException {

    public GameHasOpenSessionsException() {
        super("У игры есть открытые сессии: завершите или отмените их, либо отмените игру");
    }
}
