package club.ttg.findgame.registration;

import java.util.UUID;

/**
 * Сколько игроков принято в сессию. Проекция группового запроса: в сущностях
 * этого числа нет, оно выводится из заявок.
 */
public interface SessionPlayerCount {

    UUID getSessionId();

    long getPlayerCount();
}
