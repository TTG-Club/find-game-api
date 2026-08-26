package club.ttg.findgame.chat;

public class ChatAccessDeniedException extends RuntimeException {
    public ChatAccessDeniedException() {
        super("Чат доступен только Мастеру и принятым игрокам");
    }
}
