package club.ttg.findgame.notification;

/** О чём уведомление. Текст собирает интерфейс — здесь только повод. */
public enum NotificationType {

    /** Игрок подал заявку в сессию игры мастера. */
    REGISTRATION_SUBMITTED,

    /** Мастер принял заявку игрока. */
    REGISTRATION_APPROVED,

    /** Мастер отказал по заявке. */
    REGISTRATION_REJECTED,

    /** Игрок отозвал заявку или вышел из состава сам. */
    REGISTRATION_WITHDRAWN,

    /** Мастер исключил принятого игрока из игры. */
    PLAYER_REMOVED,

    /** В игре назначена новая встреча. */
    SESSION_SCHEDULED,

    /** Сессия началась. */
    SESSION_STARTED,

    /** Сессия завершилась. */
    SESSION_COMPLETED,

    /** Сессия отменена. */
    SESSION_CANCELLED,

    /** Игра завершена: мастер отыграл её до конца. */
    GAME_CLOSED,

    /** Игра отменена: она не состоялась. */
    GAME_CANCELLED,

    /** Отмеченный мастер объявил новую игру. */
    MASTER_PUBLISHED_GAME,

    /** Мастер зовёт отмеченного игрока в свою игру. */
    GAME_INVITE
}
