package club.ttg.findgame.nexus;

/** Действие над комнатой не имеет смысла в её нынешнем виде. */
public class InvalidNexusException extends RuntimeException {

    public InvalidNexusException(String message) {
        super(message);
    }
}
