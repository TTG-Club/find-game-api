package club.ttg.findgame.registration;

import java.util.UUID;

public class SessionRegistrationNotFoundException extends RuntimeException {

    public SessionRegistrationNotFoundException(UUID registrationId) {
        super("Заявка %s не найдена".formatted(registrationId));
    }

    private SessionRegistrationNotFoundException(String message) {
        super(message);
    }

    /**
     * Заявки на сессию нет вовсе: в отличие от конструктора по идентификатору
     * заявки, здесь известна только сессия.
     */
    public static SessionRegistrationNotFoundException forSession(UUID sessionId) {
        return new SessionRegistrationNotFoundException(
                "Заявка на сессию %s не найдена".formatted(sessionId));
    }
}
