package club.ttg.findgame.favorite;

import club.ttg.findgame.favorite.api.FavoriteGameResponse;
import club.ttg.findgame.game.GameNotFoundException;
import club.ttg.findgame.game.GameRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Избранные игры: личные закладки на объявления.
 *
 * Отметку ставят на любую существующую игру и доступа она не даёт: что из
 * отмеченного попадёт в список, решает проверка видимости при чтении. Иначе
 * пришлось бы требовать код приглашения ещё и на закладку — а приватную игру
 * человек в этот момент как раз и читает по такому коду.
 */
@Service
public class FavoriteGameService {

    private final FavoriteGameRepository repository;
    // Отметка на несуществующую игру — ошибка вызова, а не пустая закладка:
    // такой список потом молча недосчитывался бы карточек.
    private final GameRepository gameRepository;

    public FavoriteGameService(FavoriteGameRepository repository, GameRepository gameRepository) {
        this.repository = repository;
        this.gameRepository = gameRepository;
    }

    /**
     * Добавляет игру в избранное; повторная отметка ничего не меняет.
     *
     * @param ownerId Владелец списка.
     * @param gameId Отмечаемая игра.
     */
    @Transactional
    public void addFavorite(UUID ownerId, UUID gameId) {
        if (repository.existsByOwnerIdAndGameId(ownerId, gameId)) {
            return;
        }

        if (gameRepository.findByIdAndDeletedAtIsNull(gameId).isEmpty()) {
            throw new GameNotFoundException(gameId);
        }

        repository.save(FavoriteGame.of(ownerId, gameId));
    }

    /**
     * Убирает игру из избранного. Снятие несуществующей отметки — не ошибка:
     * звёздочку гасят до того, как список успел обновиться.
     *
     * @param ownerId Владелец списка.
     * @param gameId Игра, с которой снимают отметку.
     */
    @Transactional
    public void removeFavorite(UUID ownerId, UUID gameId) {
        repository.deleteByOwnerIdAndGameId(ownerId, gameId);
    }

    /**
     * Отметки владельца — свежие сверху.
     *
     * @param ownerId Владелец списка.
     */
    @Transactional(readOnly = true)
    public List<FavoriteGameResponse> findFavorites(UUID ownerId) {
        return repository.findAllByOwnerIdOrderByCreatedAtDesc(ownerId).stream()
                .map(favorite -> new FavoriteGameResponse(favorite.getGameId(), favorite.getCreatedAt()))
                .toList();
    }
}
