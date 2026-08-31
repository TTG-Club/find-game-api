package club.ttg.findgame.chat;

public enum ChatEventType {
    TEXT,
    DICE_ROLL,
    SPELL_CAST,

    /**
     * Событие самой игры, а не сообщение участника: старт и завершение
     * сессии. Пишется сервисом, автор — мастер, чьё действие его вызвало.
     */
    SYSTEM
}
