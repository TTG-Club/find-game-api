package club.ttg.findgame.game;

import java.time.Instant;

public class GameRaiseCooldownException extends RuntimeException {

    private final Instant availableAt;

    public GameRaiseCooldownException(Instant availableAt) {
        super("Игру можно будет снова поднять " + availableAt);
        this.availableAt = availableAt;
    }

    public Instant getAvailableAt() {
        return availableAt;
    }
}
