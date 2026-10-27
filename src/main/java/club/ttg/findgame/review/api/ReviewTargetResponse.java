package club.ttg.findgame.review.api;

import club.ttg.findgame.review.ReviewKind;

import java.util.UUID;

/**
 * Кого пользователь вправе оценить в игре.
 *
 * @param targetId Мастер игры для игрока или игрок для мастера.
 * @param kind Направление оценки.
 * @param review Своя уже поставленная оценка; пусто — ещё не оценивал.
 * @param editable Можно ли её поставить или поправить: раскрытую оценку не
 *                 меняют, иначе вторая сторона ответила бы на неё.
 */
public record ReviewTargetResponse(
        UUID targetId,
        ReviewKind kind,
        GameReviewResponse review,
        boolean editable
) {
}
