package club.ttg.findgame.review.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Оценка участника игры.
 *
 * @param targetId Кого оценивают: мастера или игрока этой игры.
 * @param recommended Сыграл бы снова.
 * @param comment Отзыв; необязателен — оценку ставят и молча.
 */
public record CreateGameReviewRequest(
        @NotNull UUID targetId,
        @NotNull Boolean recommended,
        @Size(max = 2000)
        @Schema(example = "Вёл ровно, правила знает, начали вовремя")
        String comment
) {
}
