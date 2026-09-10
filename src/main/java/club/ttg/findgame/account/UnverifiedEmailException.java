package club.ttg.findgame.account;

/** Действие требует подтверждённой почты: так объявления не заводят на чужие адреса. */
public class UnverifiedEmailException extends RuntimeException {

    public UnverifiedEmailException() {
        super("Подтвердите почту, чтобы создавать игры");
    }
}
