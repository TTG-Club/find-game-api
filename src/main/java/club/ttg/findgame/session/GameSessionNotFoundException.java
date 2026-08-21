package club.ttg.findgame.session;

import java.util.UUID;

public class GameSessionNotFoundException extends RuntimeException {

    public GameSessionNotFoundException(UUID sessionId) {
        super("Сессия %s не найдена".formatted(sessionId));
    }
}
