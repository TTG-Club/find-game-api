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
 * Лист персонажа, выложенный в комнату.
 *
 * Сам лист живёт в core-api — владельце этих данных; здесь только токен
 * общего доступа, по которому лист открывается всей комнате, и подпись, по
 * которой его узнают за столом.
 */
@Entity
@Table(name = "nexus_character_sheets")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NexusCharacterSheet {

    @Id
    private UUID id;

    @Column(name = "nexus_id", nullable = false)
    private UUID nexusId;

    /** Кто выложил лист: он же его и убирает. */
    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "share_token", nullable = false, length = 255)
    private String shareToken;

    @Column(name = "character_name", nullable = false, length = 100)
    private String characterName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

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
