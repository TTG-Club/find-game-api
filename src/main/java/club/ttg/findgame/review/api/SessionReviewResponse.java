package club.ttg.findgame.review.api;

import club.ttg.findgame.review.ReviewKind;

import java.time.Instant;
import java.util.UUID;

/**
 * Отзыв в том виде, в каком его показывают.
 *
 * @param id Идентификатор отзыва.
 * @param sessionId Встреча, за которую он поставлен.
 * @param gameId Игра встречи.
 * @param authorId Кто оценил.
 * @param targetId Кого оценили.
 * @param kind Направление: об игроке или о мастере.
 * @param recommended Сыграл бы снова.
 * @param comment Текст отзыва; пусто — оценку поставили молча.
 * @param createdAt Когда оценили.
 */
public record SessionReviewResponse(
        UUID id,
        UUID sessionId,
        UUID gameId,
        UUID authorId,
        UUID targetId,
        ReviewKind kind,
        boolean recommended,
        String comment,
        Instant createdAt
) {
}
