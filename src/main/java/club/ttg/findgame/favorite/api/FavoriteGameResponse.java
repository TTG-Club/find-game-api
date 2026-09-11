package club.ttg.findgame.favorite.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Отметка в списке избранного: что отмечено и когда.
 *
 * Самой игры здесь нет: список идентификаторов нужен интерфейсу, чтобы
 * показать состояние звёздочки на уже загруженных карточках, а карточки
 * избранного отдаёт постраничная выдача игр.
 */
public record FavoriteGameResponse(UUID gameId, Instant createdAt) {
}
