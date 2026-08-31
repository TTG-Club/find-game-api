package club.ttg.findgame.game;

public enum GameStatus {
    DRAFT,
    OPEN,

    /** Игра сыграна. */
    CLOSED,

    /** Игра не состоялась. Отдельный исход: «завершена» про такую — неправда. */
    CANCELLED
}
