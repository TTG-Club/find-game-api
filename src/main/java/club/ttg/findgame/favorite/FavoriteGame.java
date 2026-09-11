package club.ttg.findgame.favorite;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Закладка игрока на объявление.
 *
 * Игру откладывают, чтобы вернуться к ней позже: решиться, дождаться удобной
 * даты или просто не потерять её в выдаче. Мастеру отметка ничего не сообщает
 * и места в составе не занимает — это список у себя, а не заявка.
 */
@Entity
@Table(name = "favorite_games")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FavoriteGame {

    @Id
    private UUID id;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "game_id", nullable = false)
    private UUID gameId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static FavoriteGame of(UUID ownerId, UUID gameId) {
        FavoriteGame favorite = new FavoriteGame();

        favorite.ownerId = ownerId;
        favorite.gameId = gameId;

        return favorite;
    }

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }

        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
