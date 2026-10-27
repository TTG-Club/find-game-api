package club.ttg.findgame.review.api;

import club.ttg.findgame.review.ReviewKind;

import java.util.List;
import java.util.UUID;

/**
 * Игра, в которой пользователь ещё не оценил тех, с кем играл.
 *
 * @param gameId Игра.
 * @param gameTitle Название игры для списка.
 * @param kind Кого оценивать: мастера или игроков.
 * @param targetIds Кто ещё ждёт оценки.
 */
public record PendingReviewResponse(
        UUID gameId,
        String gameTitle,
        ReviewKind kind,
        List<UUID> targetIds
) {
}
