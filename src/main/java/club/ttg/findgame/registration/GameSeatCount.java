package club.ttg.findgame.registration;

import java.util.UUID;

/**
 * Занятость мест игры. Проекция группового запроса: в самой игре этих чисел
 * нет, они выводятся из заявок.
 */
public interface GameSeatCount {

    UUID getGameId();

    /** Места, занятые любой неотклонённой заявкой. */
    long getPlayerCount();

    /** Из них подтверждённые мастером. */
    long getApprovedCount();
}
