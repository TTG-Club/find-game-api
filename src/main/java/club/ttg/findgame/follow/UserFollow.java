package club.ttg.findgame.follow;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * Отметка одного участника о другом.
 *
 * Односторонняя и согласия второй стороны не требует: это закладка в своём
 * списке, а не дружба.
 */
@Entity
@Table(name = "user_follows")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserFollow {

    @Id
    private UUID id;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "target_id", nullable = false)
    private UUID targetId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FollowKind kind;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static UserFollow of(UUID ownerId, UUID targetId, FollowKind kind) {
        UserFollow follow = new UserFollow();

        follow.ownerId = ownerId;
        follow.targetId = targetId;
        follow.kind = kind;

        return follow;
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
