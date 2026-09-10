package club.ttg.findgame.account;

/** auth-service не ответил, и состояние учётной записи проверить нечем. */
public class AccountStatusUnavailableException extends RuntimeException {

    public AccountStatusUnavailableException() {
        super("Не удалось проверить учётную запись, попробуйте позже");
    }
}
