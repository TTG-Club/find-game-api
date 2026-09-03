package club.ttg.findgame.nexus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Трекер инициативы, заведённый в комнате.
 *
 * Сам трекер живёт в core-api — там же, где бестиарий и листы, которыми он
 * наполняется. Комната хранит ссылку на него и снимок названия, чтобы группа
 * находила свой бой, не роясь в общем списке трекеров.
 */
@Entity
@Table(name = "nexus_trackers")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NexusTracker {

    @Id
    private UUID id;

    @Column(name = "nexus_id", nullable = false)
    private UUID nexusId;

    /** Идентификатор трекера в core-api. */
    @Column(name = "tracker_id", nullable = false)
    private UUID trackerId;

    @Column(nullable = false, length = 150)
    private String title;

    /**
     * Снимок идущего боя: порядок хода, текущий боец и раунд.
     *
     * Хранится строкой, а не деревом: JSON-формат Hibernate достался от
     * Jackson 2, и объект третьего Jackson он сериализовать не берётся.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String state;

    /** Когда снимок обновляли: комната показывает бой, шевелившийся последним. */
    @Column(name = "state_updated_at")
    private Instant stateUpdatedAt;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

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
