package club.ttg.findgame.nexus;

/** Комната есть, но этому пользователю в неё нельзя. */
public class NexusAccessDeniedException extends RuntimeException {

    public NexusAccessDeniedException(String message) {
        super(message);
    }
}
