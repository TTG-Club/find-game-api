package club.ttg.findgame.follow.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Отметка в своём списке.
 *
 * Имени здесь нет: find-game-api хранит только идентификаторы, а имена
 * принадлежат core-api и подставляются на стороне сайта.
 *
 * @param userId Кого отметили.
 * @param createdAt Когда отметили.
 */
public record FollowResponse(UUID userId, Instant createdAt) {
}
