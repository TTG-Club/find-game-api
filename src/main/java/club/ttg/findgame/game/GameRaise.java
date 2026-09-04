package club.ttg.findgame.game;

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
 * Поднятие игры в списке.
 *
 * Каждое поднятие записывается отдельно: правило считает их за сутки, и по
 * одной отметке «последний раз поднимали тогда-то» его не проверить.
 */
@Entity
@Table(name = "game_raises")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GameRaise {

    @Id
    private UUID id;

    @Column(name = "game_id", nullable = false)
    private UUID gameId;

    @Column(name = "raised_at", nullable = false)
    private Instant raisedAt;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }

        if (raisedAt == null) {
            raisedAt = Instant.now();
        }
    }
}
