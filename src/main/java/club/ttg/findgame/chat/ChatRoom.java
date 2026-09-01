package club.ttg.findgame.chat;

import java.util.UUID;

/**
 * Адрес ленты. Три вида комнат: общий чат игры (пусты и сессия, и игрок),
 * чат сессии и личная переписка игрока с мастером.
 */
record ChatRoom(UUID gameId, UUID sessionId, UUID playerId) {
}
