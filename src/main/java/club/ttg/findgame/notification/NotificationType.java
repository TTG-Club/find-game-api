package club.ttg.findgame.notification;

/** О чём уведомление. Текст собирает интерфейс — здесь только повод. */
public enum NotificationType {

    /** Игрок подал заявку в сессию игры мастера. */
    REGISTRATION_SUBMITTED,

    /** Мастер принял заявку игрока. */
    REGISTRATION_APPROVED,

    /** Мастер назначил дату сессии, объявленной с открытой датой. */
    SESSION_SCHEDULED,

    /** Сессия началась. */
    SESSION_STARTED,

    /** Сессия завершилась. */
    SESSION_COMPLETED,

    /** Сессия отменена. */
    SESSION_CANCELLED
}
