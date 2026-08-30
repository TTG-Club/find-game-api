package club.ttg.findgame.registration;

import java.util.UUID;

/**
 * Сколько разных игроков принято в игру. Проекция группового запроса: в
 * сущностях этого числа нет, оно выводится из заявок.
 */
public interface GamePlayerCount {

    UUID getGameId();

    long getPlayerCount();
}
