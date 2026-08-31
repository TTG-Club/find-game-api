package club.ttg.findgame.session;

public enum GameSessionStatus {
    SCHEDULED,
    IN_PROGRESS,
    COMPLETED,

    /** Сессия не состоялась. */
    CANCELLED
}
