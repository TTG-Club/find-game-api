package club.ttg.findgame.registration;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Заявка игрока в игру.
 *
 * Заявка подаётся один раз на игру, а не на каждую сессию: принятый игрок
 * входит в состав и попадает во все запланированные встречи, включая
 * созданные позже. У сессии остаётся только то, что относится к самой
 * встрече, — присутствие и оплата.
 */
@Entity
@Table(name = "game_registrations")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GameRegistration {

    @Id
    private UUID id;

    @Column(name = "game_id", nullable = false)
    private UUID gameId;

    @Column(name = "player_id", nullable = false)
    private UUID playerId;

    @Column(name = "character_sheet_url", length = 2048)
    private String characterSheetUrl;

    /** Имя персонажа: игрок называет его, когда листа на сайте нет. */
    @Column(name = "character_name", length = 100)
    private String characterName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RegistrationStatus status;

    /**
     * Причина отказа. Необязательна: мастер вправе не объясняться, но когда
     * объясняет, игрок это видит — иначе отказ выглядит молчанием.
     */
    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }

        Instant now = Instant.now();

        if (createdAt == null) {
            createdAt = now;
        }

        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
