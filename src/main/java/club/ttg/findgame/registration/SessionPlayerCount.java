package club.ttg.findgame.registration;

import java.util.UUID;

/**
 * Занятость мест сессии. Проекция группового запроса: в сущностях этих чисел
 * нет, они выводятся из заявок.
 */
public interface SessionPlayerCount {

    UUID getSessionId();

    /** Места, занятые любой неотклонённой заявкой. */
    long getPlayerCount();

    /** Из них подтверждённые мастером. */
    long getApprovedCount();
}
