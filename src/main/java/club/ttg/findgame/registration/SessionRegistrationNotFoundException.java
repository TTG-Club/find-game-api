package club.ttg.findgame.registration;

import java.util.UUID;

public class SessionRegistrationNotFoundException extends RuntimeException {

    public SessionRegistrationNotFoundException(UUID registrationId) {
        super("Заявка %s не найдена".formatted(registrationId));
    }
}
