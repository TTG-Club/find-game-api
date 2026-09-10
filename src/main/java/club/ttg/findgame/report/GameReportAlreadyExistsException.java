package club.ttg.findgame.report;

/** Пользователь уже отправил жалобу на это объявление. */
public class GameReportAlreadyExistsException extends RuntimeException {

    public GameReportAlreadyExistsException() {
        super("Вы уже отправили жалобу на эту игру");
    }
}
