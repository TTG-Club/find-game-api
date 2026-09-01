package club.ttg.findgame.chat;

import java.util.UUID;

/**
 * Адрес ленты. Комната одна на нексус: и общий разговор группы, и события
 * игры идут в неё же.
 */
record ChatRoom(UUID nexusId) {
}
