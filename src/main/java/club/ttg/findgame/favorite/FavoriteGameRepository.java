package club.ttg.findgame.favorite;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface FavoriteGameRepository extends JpaRepository<FavoriteGame, UUID> {

    /** Свой список целиком: свежие отметки сверху. */
    List<FavoriteGame> findAllByOwnerIdOrderByCreatedAtDesc(UUID ownerId);

    boolean existsByOwnerIdAndGameId(UUID ownerId, UUID gameId);

    void deleteByOwnerIdAndGameId(UUID ownerId, UUID gameId);
}
