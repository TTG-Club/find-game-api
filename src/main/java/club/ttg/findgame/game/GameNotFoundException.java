package club.ttg.findgame.game;

import java.util.UUID;

public class GameNotFoundException extends RuntimeException {

    public GameNotFoundException(UUID gameId) {
        super("Игра %s не найдена".formatted(gameId));
    }
}
