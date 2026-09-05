package club.ttg.findgame.review.api;

import java.util.UUID;

/**
 * Репутация участника: сколько людей сыграли бы с ним снова.
 *
 * Доля, а не средний балл: оценка бинарная, и «11 из 12» читается точнее
 * любого числа с запятой.
 *
 * @param userId Участник.
 * @param recommended Сколько ответили «сыграл бы снова».
 * @param total Сколько всего раскрытых оценок.
 */
public record ReputationResponse(UUID userId, long recommended, long total) {
}
