package club.ttg.findgame.nexus;

import java.util.UUID;

/** Комнаты нет — или её не видно тому, кто спрашивает. */
public class NexusNotFoundException extends RuntimeException {

    public NexusNotFoundException(UUID nexusId) {
        super("Нексус %s не найден".formatted(nexusId));
    }

    public NexusNotFoundException(String message) {
        super(message);
    }
}
