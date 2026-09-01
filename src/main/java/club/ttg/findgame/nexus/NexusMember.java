package club.ttg.findgame.nexus;

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
 * Участник самостоятельной комнаты.
 *
 * У комнаты игры своего состава нет: его определяют заявки, и второй список
 * тех же людей рано или поздно разошёлся бы с первым.
 */
@Entity
@Table(name = "nexus_members")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NexusMember {

    @Id
    private UUID id;

    @Column(name = "nexus_id", nullable = false)
    private UUID nexusId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "joined_at", nullable = false, updatable = false)
    private Instant joinedAt;

    /**
     * Заводит участие.
     * @param nexusId Комната.
     * @param userId Пользователь.
     */
    public static NexusMember of(UUID nexusId, UUID userId) {
        NexusMember member = new NexusMember();

        member.setNexusId(nexusId);
        member.setUserId(userId);

        return member;
    }

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }

        if (joinedAt == null) {
            joinedAt = Instant.now();
        }
    }
}
